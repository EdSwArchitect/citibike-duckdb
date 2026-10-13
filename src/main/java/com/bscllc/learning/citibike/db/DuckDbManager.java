package com.bscllc.learning.citibike.db;

import com.bscllc.learning.citibike.config.CitibikeConfig;
import com.bscllc.learning.citibike.metrics.CitibikeMetrics;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@ApplicationScoped
public class DuckDbManager {
    private static final Logger LOG = LoggerFactory.getLogger(DuckDbManager.class);
    private static final String JDBC_URL = "jdbc:duckdb:memory:citibike";

    private final CitibikeConfig config;
    private final CitibikeMetrics metrics;
    private DuckDBConnection anchor;
    private BlockingQueue<DuckDBConnection> queryConnections;
    private Path tempDirectory;
    private volatile boolean ready;
    private DuckDBConnection writer;
    private final java.util.concurrent.locks.ReentrantLock writerGate = new java.util.concurrent.locks.ReentrantLock();
    private java.nio.channels.FileChannel ownershipChannel;
    private java.nio.channels.FileLock ownershipLock;
    private Path dataDirectory;
    public boolean fileMode() { return config.storage().mode().equals("file"); }
    public Path dataDirectory() { return dataDirectory; }
    public void recordCommittedData(LoadReport report){metrics.recordCommittedData(report);}
    public boolean rejectEmptyCsvFiles(){return config.csv().failOnEmpty();}
    public boolean bootstrapBundled() { return config.storage().bootstrapBundled(); }


    public DuckDbManager(CitibikeConfig config, CitibikeMetrics metrics) {
        this.config = config;
        this.metrics = metrics;
    }

    public synchronized void start() {
        if (anchor != null) {
            return;
        }
        validateConfiguration();
        try {
            Class.forName("org.duckdb.DuckDBDriver");
            String url=JDBC_URL;
            if(fileMode()) {
                if(!config.storage().databasePath().matches("[A-Za-z0-9_-]+\\.duckdb"))throw new IOException("Invalid database filename");
                dataDirectory=Path.of(config.storage().dataDirectory()).toAbsolutePath().normalize();
                Files.createDirectories(dataDirectory);
                if(Files.isSymbolicLink(dataDirectory) || Files.isSymbolicLink(dataDirectory.resolve("owner.lock")))throw new IOException("Unsafe storage directory");
                ownershipChannel=java.nio.channels.FileChannel.open(dataDirectory.resolve("owner.lock"),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE);
                try { ownershipLock=ownershipChannel.tryLock(); } catch(java.nio.channels.OverlappingFileLockException e) { ownershipLock=null; }
                if(ownershipLock==null)throw new IOException("Storage already owned");
                Path database=dataDirectory.resolve(config.storage().databasePath());
                if(Files.isSymbolicLink(database))throw new IOException("Unsafe database file");
                url="jdbc:duckdb:"+database;
                tempDirectory=Files.createTempDirectory(dataDirectory,"spill-");
            } else if(config.storage().mode().equals("memory"))tempDirectory = Files.createTempDirectory("duckdb-citibike-");
            else throw new IOException("Invalid storage mode");
            Properties properties = new Properties();
            properties.setProperty("threads", Integer.toString(config.duckdb().threads()));
            properties.setProperty("memory_limit", config.duckdb().memoryLimit());
            properties.setProperty("max_temp_directory_size", config.duckdb().maxTempDirectorySize());
            properties.setProperty("temp_directory", tempDirectory.toString());
            properties.setProperty("custom_user_agent", "duckdb-learn-quarkus");
            anchor = (DuckDBConnection) DriverManager.getConnection(url, properties);
            if(fileMode())writer=anchor.duplicate();
            if(fileMode()) {
                SchemaMigrator.migrate(anchor);
                try(var old=Files.list(dataDirectory)) {
                    for(Path entry:old.toList())if(!entry.equals(tempDirectory) && entry.getFileName().toString().startsWith("spill-"))
                        com.bscllc.learning.citibike.ingestion.MonthlyImporter.deleteDirectory(entry);
                }
            }
            LOG.info("Started shared DuckDB instance with {} threads", config.duckdb().threads());
        } catch (ClassNotFoundException | SQLException | IOException e) {
            shutdown();
            throw new IllegalStateException("Unable to start DuckDB; check storage ownership, migrations, and resource limits");
        }
    }

    Connection bootstrapConnection() {
        if (anchor == null) {
            throw new IllegalStateException("DuckDB has not been started");
        }
        return anchor;
    }

    public synchronized void activate() { activate(true); }

    public synchronized void activate(boolean dataReady) {
        if (queryConnections != null) {
            ready=dataReady;
            return;
        }
        queryConnections = new ArrayBlockingQueue<>(config.jdbc().maxConnections());
        try {
            for (int i = 0; i < config.jdbc().maxConnections(); i++) {
                queryConnections.add(anchor.duplicate());
            }
            ready = dataReady;
        } catch (SQLException e) {
            closeConnections();
            throw new IllegalStateException("Unable to create DuckDB query connections", e);
        }
    }

    public void cancelWriter() {
        var current=writer;if(current!=null)try(var statement=current.createStatement()){statement.cancel();}catch(SQLException ignored){}
    }
    public <T> T withWriter(SqlWork<T> work) {
        boolean locked=false;
        try {
            locked=writerGate.tryLock(config.jdbc().acquireTimeout().toMillis(),TimeUnit.MILLISECONDS);
            if(!locked || writer==null)throw new DatabaseBusyException("Ingestion writer is busy");
            return work.execute(writer);
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new DatabaseBusyException("Ingestion writer interrupted"); }
        catch(SQLException e) { throw new DataAccessException("Ingestion database operation failed", e); }
        finally { if(locked)writerGate.unlock(); }
    }
    public <T> T withSnapshot(SqlWork<T> work) {
        return withConnection(c -> {
            c.setAutoCommit(false);
            try { T result=work.execute(c);c.commit();return result; }
            catch(SQLException|RuntimeException e){c.rollback();throw e;}
            finally{c.setAutoCommit(true);}
        });
    }
    public <T> T withMetadata(SqlWork<T> work) { return lease(work,false); }
    public <T> T withConnection(SqlWork<T> work) { return lease(work,true); }
    private <T> T lease(SqlWork<T> work, boolean requireReady) {
        BlockingQueue<DuckDBConnection> pool = queryConnections;
        if ((requireReady && !ready) || pool == null) {
            throw new DatabaseBusyException("Ride data is not ready");
        }

        DuckDBConnection connection;
        try {
            connection = pool.poll(
                    config.jdbc().acquireTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DatabaseBusyException("Interrupted while waiting for DuckDB");
        }
        if (connection == null) {
            throw new DatabaseBusyException("DuckDB query capacity is temporarily exhausted");
        }

        synchronized (this) {
            if ((requireReady && !ready) || anchor==null) {
                closeQuietly(connection);
                throw new DatabaseBusyException("Ride data is not ready");
            }
            metrics.connectionAcquired();
        }
        try {
            return work.execute(connection);
        } catch (SQLException e) {
            throw new DataAccessException("DuckDB query failed", e);
        } finally {
            metrics.connectionReleased();
            synchronized (this) {
                if (anchor==null || pool!=queryConnections || !pool.offer(connection)) {
                    closeQuietly(connection);
                }
            }
        }
    }

    private void validateConfiguration() {
        if (config.jdbc().queryTimeout().compareTo(java.time.Duration.ofMillis(1)) < 0
                || config.jdbc().queryTimeout().compareTo(java.time.Duration.ofSeconds(60)) > 0
                || config.jdbc().acquireTimeout().compareTo(java.time.Duration.ofMillis(1)) < 0
                || config.jdbc().acquireTimeout().compareTo(java.time.Duration.ofSeconds(5)) > 0
                || config.jdbc().maxConnections() < 1 || config.jdbc().maxConnections() > 32
                || config.duckdb().threads() < 1 || config.duckdb().threads() > 32
                || config.api().maxPageSize() < 1 || config.api().maxPageSize() > 100
                || config.api().maxDateRangeDays() < 1 || config.api().maxDateRangeDays() > 366
                || config.api().maxOffset() < 0 || config.api().maxOffset() > 1_000_000
                || !config.duckdb().memoryLimit().matches("[1-9][0-9]*(KB|MB|GB)")
                || !config.duckdb().maxTempDirectorySize().matches("[1-9][0-9]*(KB|MB|GB)")) {
            throw new IllegalStateException("Invalid Citi Bike resource-limit configuration");
        }
    }

    Path importDirectory() {
        if (tempDirectory == null) throw new IllegalStateException("DuckDB has not been started");
        return tempDirectory;
    }

    public int queryTimeoutSeconds() {
        long millis = config.jdbc().queryTimeout().toMillis();
        return Math.max(1, Math.toIntExact((millis + 999) / 1000));
    }

    public boolean isReady() {
        return ready;
    }

    void markNotReady() {
        ready = false;
    }

    void onShutdown(@Observes ShutdownEvent ignored) {
        shutdown();
    }

    public synchronized void shutdown() {
        ready = false;
        if (anchor == null && queryConnections == null && tempDirectory == null && ownershipChannel==null) return;
        closeConnections();
        closeQuietly(writer);
        writer=null;
        closeQuietly(anchor);
        LOG.info("Stopped shared DuckDB instance");
        anchor = null;
        deleteTempDirectory();
        try { if(ownershipLock!=null)ownershipLock.release(); }catch(IOException ignored){}
        try { if(ownershipChannel!=null)ownershipChannel.close(); }catch(IOException ignored){}
        ownershipLock=null;ownershipChannel=null;
    }

    private void closeConnections() {
        if (queryConnections == null) {
            return;
        }
        DuckDBConnection connection;
        while ((connection = queryConnections.poll()) != null) {
            closeQuietly(connection);
        }
        queryConnections = null;
    }

    private void deleteTempDirectory() {
        if (tempDirectory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(tempDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOG.debug("Unable to delete DuckDB temporary file");
                }
            });
        } catch (IOException e) {
            LOG.debug("Unable to clean DuckDB temporary directory");
        } finally {
            tempDirectory = null;
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            LOG.debug("Unable to close DuckDB connection");
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }
}

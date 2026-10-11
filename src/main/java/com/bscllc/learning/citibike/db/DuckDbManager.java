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
            tempDirectory = Files.createTempDirectory("duckdb-citibike-");
            Properties properties = new Properties();
            properties.setProperty("threads", Integer.toString(config.duckdb().threads()));
            properties.setProperty("memory_limit", config.duckdb().memoryLimit());
            properties.setProperty("max_temp_directory_size", config.duckdb().maxTempDirectorySize());
            properties.setProperty("temp_directory", tempDirectory.toString());
            properties.setProperty("custom_user_agent", "duckdb-learn-quarkus");
            anchor = (DuckDBConnection) DriverManager.getConnection(JDBC_URL, properties);
            LOG.info("Started shared DuckDB instance with {} threads", config.duckdb().threads());
        } catch (ClassNotFoundException | SQLException | IOException e) {
            deleteTempDirectory();
            throw new IllegalStateException("Unable to start DuckDB; check configured resource limits");
        }
    }

    Connection bootstrapConnection() {
        if (anchor == null) {
            throw new IllegalStateException("DuckDB has not been started");
        }
        return anchor;
    }

    public synchronized void activate() {
        if (queryConnections != null) {
            return;
        }
        queryConnections = new ArrayBlockingQueue<>(config.jdbc().maxConnections());
        try {
            for (int i = 0; i < config.jdbc().maxConnections(); i++) {
                queryConnections.add(anchor.duplicate());
            }
            ready = true;
        } catch (SQLException e) {
            closeConnections();
            throw new IllegalStateException("Unable to create DuckDB query connections", e);
        }
    }

    public <T> T withConnection(SqlWork<T> work) {
        BlockingQueue<DuckDBConnection> pool = queryConnections;
        if (!ready || pool == null) {
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
            if (!ready) {
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
                if (!ready || !pool.offer(connection)) {
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
        if (anchor == null && queryConnections == null && tempDirectory == null) return;
        closeConnections();
        closeQuietly(anchor);
        LOG.info("Stopped shared DuckDB instance");
        anchor = null;
        deleteTempDirectory();
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

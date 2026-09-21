package com.bscllc.learning.citibike.db;

import com.bscllc.learning.citibike.config.CitibikeConfig;
import com.bscllc.learning.citibike.db.CsvResourceCatalog.CsvResource;
import com.bscllc.learning.citibike.metrics.CitibikeMetrics;
import io.micrometer.core.instrument.Timer;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class CitibikeDataLoader {
    private static final Logger LOG = LoggerFactory.getLogger(CitibikeDataLoader.class);
    private static final List<String> EXPECTED_HEADER = List.of(
            "ride_id", "rideable_type", "started_at", "ended_at",
            "start_station_name", "start_station_id", "end_station_name",
            "end_station_id", "start_lat", "start_lng", "end_lat", "end_lng",
            "member_casual");

    private static final String CREATE_STAGING_SQL = """
            CREATE TABLE rides_staging (
                ride_id VARCHAR NOT NULL,
                rideable_type VARCHAR NOT NULL,
                started_at TIMESTAMP NOT NULL,
                ended_at TIMESTAMP NOT NULL,
                start_station_name VARCHAR,
                start_station_id VARCHAR,
                end_station_name VARCHAR,
                end_station_id VARCHAR,
                start_lat DOUBLE NOT NULL,
                start_lng DOUBLE NOT NULL,
                end_lat DOUBLE,
                end_lng DOUBLE,
                member_casual VARCHAR NOT NULL,
                source_file VARCHAR NOT NULL
            )
            """;

    private static final String LOAD_CSV_SQL = """
            INSERT INTO rides_staging
            SELECT ride_id, rideable_type, started_at, ended_at,
                   start_station_name, start_station_id, end_station_name, end_station_id,
                   start_lat, start_lng, end_lat, end_lng, member_casual, ?
            FROM read_csv(?, header = true, strict_mode = true, columns = {
                'ride_id': 'VARCHAR',
                'rideable_type': 'VARCHAR',
                'started_at': 'TIMESTAMP',
                'ended_at': 'TIMESTAMP',
                'start_station_name': 'VARCHAR',
                'start_station_id': 'VARCHAR',
                'end_station_name': 'VARCHAR',
                'end_station_id': 'VARCHAR',
                'start_lat': 'DOUBLE',
                'start_lng': 'DOUBLE',
                'end_lat': 'DOUBLE',
                'end_lng': 'DOUBLE',
                'member_casual': 'VARCHAR'
            })
            """;

    private final DuckDbManager duckDb;
    private final CsvResourceCatalog catalog;
    private final CitibikeConfig config;
    private final CitibikeMetrics metrics;
    private volatile LoadReport report;

    public CitibikeDataLoader(DuckDbManager duckDb, CsvResourceCatalog catalog,
                              CitibikeConfig config, CitibikeMetrics metrics) {
        this.duckDb = duckDb;
        this.catalog = catalog;
        this.config = config;
        this.metrics = metrics;
    }

    void onStart(@Observes StartupEvent ignored) {
        Timer.Sample timer = metrics.startTimer();
        try {
            duckDb.start();
            List<CsvResource> resources = catalog.discover();
            LOG.info("Discovered {} Citi Bike CSV resource(s)", resources.size());
            report = load(resources);
            duckDb.activate();
            metrics.recordLoadSuccess(timer, resources.size(), report.filesLoaded(), report.totalRows());
            LOG.info("Loaded {} ride rows from {} CSV resource(s)",
                    report.totalRows(), report.filesLoaded());
        } catch (RuntimeException e) {
            metrics.recordLoadFailure(timer, failureCategory(e));
            duckDb.markNotReady();
            duckDb.shutdown();
            throw e;
        }
    }

    public LoadReport report() {
        LoadReport current = report;
        if (current == null) {
            throw new DatabaseBusyException("CSV loading has not completed");
        }
        return current;
    }

    private LoadReport load(List<CsvResource> resources) {
        Connection connection = duckDb.bootstrapConnection();
        Map<String, Long> rowsBySource = new LinkedHashMap<>();
        boolean previousAutoCommit;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to begin CSV load transaction", e);
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS rides_staging");
            statement.execute(CREATE_STAGING_SQL);

            for (CsvResource resource : resources) {
                Timer.Sample fileTimer = metrics.startTimer();
                try {
                    long before = countRows(connection);
                    loadOne(connection, resource);
                    long loaded = countRows(connection) - before;
                    rowsBySource.put(resource.name(), loaded);
                    metrics.recordFileLoad(fileTimer, true);
                    LOG.info("Loaded CSV resource {} with {} rows", resource.name(), loaded);
                } catch (IOException | SQLException | RuntimeException e) {
                    metrics.recordFileLoad(fileTimer, false);
                    throw e;
                }
            }

            validateLoadedData(connection);
            LocalDateTime[] bounds = readBounds(connection);
            statement.execute("DROP TABLE IF EXISTS rides");
            statement.execute("ALTER TABLE rides_staging RENAME TO rides");
            connection.commit();
            return new LoadReport(resources.size(), resources.size(),
                    rowsBySource.values().stream().mapToLong(Long::longValue).sum(),
                    rowsBySource, bounds[0], bounds[1], Instant.now());
        } catch (SQLException | IOException | RuntimeException e) {
            rollback(connection);
            throw new IllegalStateException("Unable to load Citi Bike CSV resources", e);
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException e) {
                LOG.warn("Unable to restore DuckDB auto-commit mode", e);
            }
        }
    }

    private void loadOne(Connection connection, CsvResource resource) throws IOException, SQLException {
        validateHeader(resource);
        Path extracted = Files.createTempFile("citibike-import-", ".csv");
        try {
            try (var input = resource.openStream()) {
                Files.copy(input, extracted, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try (PreparedStatement statement = connection.prepareStatement(LOAD_CSV_SQL)) {
                statement.setString(1, resource.name());
                statement.setString(2, extracted.toAbsolutePath().toString());
                statement.executeUpdate();
            }
        } finally {
            Files.deleteIfExists(extracted);
        }
    }

    private static void validateHeader(CsvResource resource) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                throw new IllegalStateException("CSV resource is empty: " + resource.name());
            }
            if (header.startsWith("\uFEFF")) {
                header = header.substring(1);
            }
            List<String> columns = Arrays.stream(header.split(",", -1))
                    .map(String::trim)
                    .map(CitibikeDataLoader::stripQuotes)
                    .toList();
            if (!columns.equals(EXPECTED_HEADER)) {
                throw new IllegalStateException("Unexpected CSV header in " + resource.name());
            }
        }
    }

    private void validateLoadedData(Connection connection) throws SQLException {
        assertNoRows(connection, """
                SELECT 1 FROM rides_staging
                WHERE rideable_type NOT IN ('classic_bike', 'electric_bike', 'docked_bike')
                   OR member_casual NOT IN ('member', 'casual')
                LIMIT 1
                """, "CSV contains an unsupported categorical value");
        assertNoRows(connection, """
                SELECT 1 FROM rides_staging
                WHERE ended_at <= started_at
                LIMIT 1
                """, "CSV contains a non-positive ride duration");
        assertNoRows(connection, """
                SELECT 1 FROM rides_staging
                WHERE start_lat NOT BETWEEN -90 AND 90
                   OR start_lng NOT BETWEEN -180 AND 180
                   OR (end_lat IS NULL) <> (end_lng IS NULL)
                   OR (end_lat IS NOT NULL AND end_lat NOT BETWEEN -90 AND 90)
                   OR (end_lng IS NOT NULL AND end_lng NOT BETWEEN -180 AND 180)
                LIMIT 1
                """, "CSV contains invalid coordinates");
        if (config.csv().failOnDuplicateRideId()) {
            assertNoRows(connection, """
                    SELECT ride_id FROM rides_staging
                    GROUP BY ride_id HAVING count(*) > 1
                    LIMIT 1
                    """, "Duplicate ride_id found across CSV resources");
        }
    }

    private static void assertNoRows(Connection connection, String sql, String message)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (result.next()) {
                throw new IllegalStateException(message);
            }
        }
    }

    private static long countRows(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) FROM rides_staging")) {
            result.next();
            return result.getLong(1);
        }
    }

    private static LocalDateTime[] readBounds(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT min(started_at), max(started_at) FROM rides_staging")) {
            result.next();
            if (result.getTimestamp(1) == null || result.getTimestamp(2) == null) {
                throw new IllegalStateException("No ride rows were loaded from CSV resources");
            }
            return new LocalDateTime[]{
                    result.getTimestamp(1).toLocalDateTime(),
                    result.getTimestamp(2).toLocalDateTime()
            };
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            LOG.warn("Unable to roll back failed CSV load", e);
        }
    }

    private static String failureCategory(RuntimeException exception) {
        Set<Class<?>> validationTypes = Set.of(IllegalArgumentException.class, IllegalStateException.class);
        return validationTypes.contains(exception.getClass()) ? "validation" : "runtime";
    }
}

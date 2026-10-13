package com.bscllc.learning.citibike.db;

import com.bscllc.learning.citibike.config.CitibikeConfig;
import com.bscllc.learning.citibike.health.DuckDbReadinessCheck;
import com.bscllc.learning.citibike.metrics.CitibikeMetrics;
import com.bscllc.learning.citibike.repository.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import static com.bscllc.learning.citibike.support.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class LoaderAndLifecycleTest {
    @TempDir Path directory;
    SimpleMeterRegistry registry;
    CitibikeMetrics metrics;
    CitibikeConfig configuration;
    DuckDbManager manager;
    CitibikeDataLoader loader;

    @BeforeEach void setup() throws Exception {
        configuration = config(Map.of("citibike.jdbc.max-connections", "1", "citibike.jdbc.acquire-timeout", "25ms", "citibike.jdbc.query-timeout", "1s"));
        registry = new SimpleMeterRegistry();
        metrics = new CitibikeMetrics(registry);
        manager = new DuckDbManager(configuration, metrics);
        loader = new CitibikeDataLoader(manager, new CsvResourceCatalog(configuration, directory.toUri().toURL()), configuration, metrics);
    }

    @AfterEach void cleanup() {
        if (manager != null) manager.shutdown();
        if (registry != null) registry.close();
    }

    private List<CsvResourceCatalog.CsvResource> resources(String... bodies) throws Exception {
        List<CsvResourceCatalog.CsvResource> resources = new ArrayList<>();
        for (int i = 0; i < bodies.length; i++) {
            String name = "part-" + i + ".csv";
            Path file = directory.resolve(name);
            Files.writeString(file, bodies[i]);
            resources.add(new CsvResourceCatalog.CsvResource(name, file.toUri().toURL()));
        }
        Path index = directory.resolve(CsvResourceCatalog.INDEX_RESOURCE);
        Files.createDirectories(index.getParent());
        Files.writeString(index, String.join("\n", resources.stream().map(CsvResourceCatalog.CsvResource::name).toList()));
        return resources;
    }

    @Test void loadsAllFilesWithTextIdsProvenanceAndExactPercentiles() throws Exception {
        resources(HEADER + ROW, HEADER + ROW.replace("R1,", "R2,").replace("08:10:00", "08:30:00").replace("Station B,002.030", "Station A,001.020"));
        DuckDbReadinessCheck health = new DuckDbReadinessCheck(manager, loader);
        manager.start();
        assertFalse(manager.isReady());
        assertEquals(org.eclipse.microprofile.health.HealthCheckResponse.Status.DOWN, health.call().getStatus());
        loader.onStart(null);
        assertEquals(2, loader.report().totalRows());
        assertEquals(Map.of("part-0.csv", 1L, "part-1.csv", 1L), loader.report().rowsBySource());
        manager.withConnection(connection -> {
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT start_station_id, source_file FROM rides ORDER BY ride_id")) {
                assertTrue(result.next());
                assertEquals("001.020", result.getString(1));
                assertEquals("part-0.csv", result.getString(2));
                assertEquals(Types.VARCHAR, result.getMetaData().getColumnType(1));
                assertTrue(result.next());
                assertEquals("part-1.csv", result.getString(2));
            }
            return null;
        });
        var repository = new RideAnalyticsRepository(manager, metrics);
        var summary = repository.summary(new RideFilter(LocalDate.of(2023, 7, 1), LocalDate.of(2023, 7, 1), null, null));
        assertEquals(2, summary.rideCount());
        assertEquals(1200.0, summary.averageDurationSeconds());
        assertEquals(1200.0, summary.medianDurationSeconds());
        assertEquals(1680.0, summary.p90DurationSeconds(), 0.001);
        assertEquals(1740.0, summary.p95DurationSeconds(), 0.001);
        assertEquals(1, summary.roundTripCount());
        var filter = new RideFilter(LocalDate.of(2023, 7, 1), LocalDate.of(2023, 7, 1), null, null);
        assertEquals(2, repository.timeseries(filter, com.bscllc.learning.citibike.validation.TimeInterval.HOUR).getFirst().rideCount());
        assertEquals(LocalDateTime.of(2023, 7, 1, 8, 0), repository.timeseries(filter, com.bscllc.learning.citibike.validation.TimeInterval.HOUR).getFirst().bucket());
        assertEquals(100.0, repository.byBikeType(filter.from(), filter.to()).getFirst().percentage());
        assertEquals("classic_bike", repository.byBikeType(filter.from(), filter.to()).getFirst().category());
        assertEquals("member", repository.byRiderType(filter.from(), filter.to()).getFirst().category());
        assertEquals(1200.0, repository.byRiderType(filter.from(), filter.to()).getFirst().averageDurationSeconds());
        var activity = repository.activity(filter, com.bscllc.learning.citibike.validation.ActivityGroup.DAY_OF_WEEK).getFirst();
        assertEquals("Saturday", activity.label());
        assertEquals(2, activity.rideCount());
        var stations = repository.stations(filter, com.bscllc.learning.citibike.validation.StationDirection.START,
                com.bscllc.learning.citibike.validation.StationSort.RIDE_COUNT,
                com.bscllc.learning.citibike.validation.SortDirection.DESC, 20, 0, null);
        assertEquals(1, stations.total());
        assertEquals(2, stations.items().getFirst().rideCount());
        assertEquals("001.020", stations.items().getFirst().stationId());
        var routes = repository.routes(filter, false, com.bscllc.learning.citibike.validation.RouteSort.RIDE_COUNT,
                com.bscllc.learning.citibike.validation.SortDirection.DESC, 20, 0);
        assertEquals(1, routes.total());
        assertEquals(600.0, routes.items().getFirst().averageDurationSeconds());
        assertEquals(2, repository.routes(filter, true, com.bscllc.learning.citibike.validation.RouteSort.RIDE_COUNT,
                com.bscllc.learning.citibike.validation.SortDirection.DESC, 20, 0).total());
        var imbalance = repository.imbalance(filter.from(), filter.to(), 20, 0);
        assertEquals(2, imbalance.total());
        var stationA = imbalance.items().stream().filter(item -> "001.020".equals(item.stationId())).findFirst().orElseThrow();
        assertEquals(2, stationA.starts());
        assertEquals(1, stationA.ends());
        assertEquals(1, stationA.netStarts());
        assertEquals(0, repository.dataQuality().duplicateRideIds());
        assertEquals(org.eclipse.microprofile.health.HealthCheckResponse.Status.UP, health.call().getStatus());
        assertEquals(2, registry.get("citibike.csv.files.discovered").gauge().value());
        assertEquals(2, registry.get("citibike.csv.rows").gauge().value());
        assertEquals(0, registry.get("citibike.jdbc.connections.active").gauge().value());
        try (var paths = Files.list(manager.importDirectory())) { assertEquals(0, paths.count()); }
    }

    static Stream<String> invalidCsvs() {
        return Stream.of(
                "wrong,header\n1,2\n", HEADER + "too,few,columns\n", HEADER + ROW.replace(",member\n", ",member,extra\n"), HEADER,
                HEADER + ROW.replace("2023-07-01 08:00:00", "invalid-time"),
                HEADER + ROW.replace("2023-07-01 08:00:00", "-infinity"),
                HEADER + ROW.replace("classic_bike", "unknown_bike"),
                HEADER + ROW.replace(",member", ",unknown"),
                HEADER + ROW.replace("08:10:00", "07:59:00"),
                HEADER + ROW.replace("08:10:00", "08:00:00"),
                HEADER + ROW.replace("40.7,", "91,"),
                HEADER + ROW.replace("40.71,-73.91", "40.71,"),
                HEADER + ROW.replace("R1,", ","),
                HEADER + ROW.replace("R1,", "   ,"),
                HEADER + ROW.replace("40.7,", ","),
                HEADER + ROW + ROW);
    }

    @ParameterizedTest @MethodSource("invalidCsvs")
    void invalidFileRollsBackEntireLoadAndLeavesOldTable(String invalid) throws Exception {
        manager.start();
        try (var statement = manager.bootstrapConnection().createStatement()) {
            statement.execute("CREATE TABLE rides AS SELECT 'previous' AS marker");
        }
        var resources = resources(HEADER + ROW.replace("R1,", "VALID,"), invalid);
        assertThrows(IllegalStateException.class, () -> loader.load(resources));
        try (var statement = manager.bootstrapConnection().createStatement(); var result = statement.executeQuery("SELECT marker FROM rides")) {
            assertTrue(result.next());
            assertEquals("previous", result.getString(1));
            assertFalse(result.next());
        }
        try (var statement = manager.bootstrapConnection().createStatement(); var result = statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_name='rides_staging'")) {
            result.next();
            assertEquals(0, result.getLong(1));
        }
        assertTrue(manager.bootstrapConnection().getAutoCommit());
        assertFalse(manager.isReady());
        try (var paths = Files.list(manager.importDirectory())) { assertEquals(0, paths.count()); }
    }

    @Test void duplicatesAcrossFilesFailStartupAndKeepReadinessDown() throws Exception {
        resources(HEADER + ROW, HEADER + ROW);
        var exception = assertThrows(IllegalStateException.class, () -> loader.onStart(null));
        assertTrue(exception.getMessage().contains("Duplicate ride_id"));
        assertFalse(manager.isReady());
        assertThrows(DatabaseBusyException.class, loader::report);
        assertEquals(2, registry.get("citibike.csv.files.discovered").gauge().value());
        assertEquals(0, registry.get("citibike.csv.files.loaded").gauge().value());
        assertEquals(1, registry.get("citibike.csv.load.failures").tag("reason", "validation").counter().count());
    }

    @Test void noMatchingFilesFailStartup() {
        assertThrows(IllegalStateException.class, () -> loader.onStart(null));
        assertFalse(manager.isReady());
    }

    @Test void duplicateResourceNamesAreRejectedBeforeLoad() throws Exception {
        manager.start();
        var resources = resources(HEADER + ROW);
        assertThrows(IllegalStateException.class, () -> loader.load(List.of(resources.getFirst(), resources.getFirst())));
    }

    @Test void unavailableAndSaturatedPoolAreBoundedAndRecover() throws Exception {
        assertThrows(DatabaseBusyException.class, () -> manager.withConnection(c -> null));
        resources(HEADER + ROW);
        loader.onStart(null);
        manager.withConnection(connection -> {
            long started = System.nanoTime();
            assertThrows(DatabaseBusyException.class, () -> manager.withConnection(c -> null));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1000);
            assertEquals(org.eclipse.microprofile.health.HealthCheckResponse.Status.DOWN, new DuckDbReadinessCheck(manager, loader).call().getStatus());
            assertEquals(1, registry.get("citibike.jdbc.connections.active").gauge().value());
            return null;
        });
        assertThrows(DataAccessException.class, () -> manager.withConnection(connection -> { throw new SQLException("synthetic failure"); }));
        assertThrows(IllegalStateException.class, () -> manager.withConnection(connection -> { throw new IllegalStateException("synthetic failure"); }));
        assertEquals(0, registry.get("citibike.jdbc.connections.active").gauge().value());
        assertEquals(Integer.valueOf(1), manager.<Integer>withConnection(connection -> {
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT 1")) { result.next(); return result.getInt(1); }
        }));
    }

    @Test void statementTimeoutCancelsRealQueryAndConnectionRemainsUsable() throws Exception {
        resources(HEADER + ROW);
        loader.onStart(null);
        long started = System.nanoTime();
        DataAccessException timeout = assertThrows(DataAccessException.class, () -> manager.withConnection(connection -> {
            try (var statement = connection.prepareStatement("SELECT sum(sin(i)) FROM range(10000000000) t(i)")) {
                statement.setQueryTimeout(manager.queryTimeoutSeconds());
                statement.executeQuery();
            }
            return null;
        }));
        assertInstanceOf(SQLTimeoutException.class, timeout.getCause());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 8000);
        assertEquals(0, registry.get("citibike.jdbc.connections.active").gauge().value());
        manager.withConnection(connection -> {
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT count(*) FROM rides")) { result.next(); assertEquals(1, result.getLong(1)); }
            return null;
        });
    }

    @Test void shutdownDuringLeaseClosesConnectionWithoutReturnRace() throws Exception {
        resources(HEADER + ROW);
        loader.onStart(null);
        Connection[] held = new Connection[1];
        manager.withConnection(connection -> {
            held[0] = connection;
            manager.shutdown();
            return null;
        });
        assertTrue(held[0].isClosed());
        assertEquals(0, registry.get("citibike.jdbc.connections.active").gauge().value());
        assertThrows(DatabaseBusyException.class, () -> manager.withConnection(c -> null));
    }

    @Test void rejectsInvalidResourceLimitConfiguration() {
        for (var override : List.of(Map.of("citibike.jdbc.max-connections", "0"), Map.of("citibike.jdbc.query-timeout", "0s"), Map.of("citibike.jdbc.acquire-timeout", "-1s"), Map.of("citibike.api.max-page-size", "101"), Map.of("citibike.duckdb.threads", "0"), Map.of("citibike.duckdb.memory-limit", "unlimited"))) {
            var invalid = new DuckDbManager(config(override), metrics);
            assertThrows(IllegalStateException.class, invalid::start);
            invalid.shutdown();
        }
    }
    @Test void dataQualityCountsInvalidRowsAndOptionalMissingFields() throws Exception {
        resources(HEADER + ROW.replace("Station A,001.020", ",").replace("40.71,-73.91", ","));
        loader.onStart(null);
        var repository = new RideAnalyticsRepository(manager, metrics);
        var quality = repository.dataQuality();
        assertEquals(0, quality.invalidRows());
        assertEquals(1, quality.missingValues().get("startStationName"));
        assertEquals(1, quality.missingValues().get("endLat"));
        manager.withConnection(connection -> {
            try (var statement = connection.createStatement()) { statement.execute("UPDATE rides SET member_casual='unknown'"); }
            return null;
        });
        assertEquals(1, repository.dataQuality().invalidRows());
    }

    @Test void failedStartupLogsNeverExposeCsvValuesSqlOrTemporaryPaths() throws Exception {
        resources(HEADER + ROW.replace("2023-07-01 08:00:00", "LEAK_PROBE' SELECT secret"));
        try (var logs = new com.bscllc.learning.citibike.support.LogCapture()) {
            assertThrows(IllegalStateException.class, () -> loader.onStart(null));
            assertTrue(logs.content().contains("part-0.csv"));
            assertFalse(logs.content().contains("LEAK_PROBE"));
            assertFalse(logs.content().contains("SELECT "));
            assertFalse(logs.content().contains("citibike-import-"));
        }
    }

    @Test void queriesCannotSeeStagingRowsBeforeAtomicCommit() throws Exception {
        manager.start();
        try (var statement = manager.bootstrapConnection().createStatement()) {
            statement.execute("CREATE TABLE rides AS SELECT 'previous' AS ride_id");
        }
        manager.activate();
        var first = resources(HEADER + ROW).getFirst();
        CountDownLatch reachedSecondFile = new CountDownLatch(1);
        CountDownLatch continueLoading = new CountDownLatch(1);
        var blockedUrl = new java.net.URL(null, "fixture:second", new java.net.URLStreamHandler() {
            @Override protected java.net.URLConnection openConnection(java.net.URL url) {
                return new java.net.URLConnection(url) {
                    @Override public void connect() {}
                    @Override public java.io.InputStream getInputStream() throws java.io.IOException {
                        reachedSecondFile.countDown();
                        try {
                            if (!continueLoading.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Fixture wait timed out");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new java.io.IOException("Fixture interrupted");
                        }
                        return new java.io.ByteArrayInputStream((HEADER + ROW.replace("R1,", "R2,")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                };
            }
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var loading = executor.submit(() -> loader.load(List.of(first, new CsvResourceCatalog.CsvResource("second.csv", blockedUrl))));
            try {
                assertTrue(reachedSecondFile.await(5, TimeUnit.SECONDS));
                manager.withConnection(connection -> {
                    try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT ride_id FROM rides")) {
                        assertTrue(result.next());
                        assertEquals("previous", result.getString(1));
                        assertFalse(result.next());
                    }
                    return null;
                });
            } finally {
                continueLoading.countDown();
            }
            assertEquals(2, loading.get(5, TimeUnit.SECONDS).totalRows());
        }
        manager.withConnection(connection -> {
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT count(*) FROM rides")) {
                result.next();
                assertEquals(2, result.getLong(1));
            }
            return null;
        });
    }

}

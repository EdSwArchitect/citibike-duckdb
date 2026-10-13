package com.bscllc.learning.citibike;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@io.quarkus.test.junit.TestProfile(MultiFileProfile.class)
class RideAnalyticsResourceTest {
    @jakarta.inject.Inject com.bscllc.learning.citibike.db.DuckDbManager manager;
    @jakarta.inject.Inject com.bscllc.learning.citibike.config.CitibikeConfig config;
    @jakarta.inject.Inject io.micrometer.core.instrument.MeterRegistry registry;
    @Test
    void returnsRideSummaryForTheLoadedResources() {
        given()
                .when().get("/api/v1/rides/summary")
                .then()
                .statusCode(200)
                .body("from", equalTo("2023-07-01"))
                .body("to", equalTo("2023-07-02"))
                .body("rideCount", equalTo(2))
                .body("medianDurationSeconds", equalTo(900.0f))
                .body("ridesOver24Hours", equalTo(0));
    }

    @Test
    void supportsAllAnalyticalQueryFamilies() {
        given().when().get("/api/v1/rides/timeseries?interval=day")
                .then().statusCode(200).body("size()", equalTo(2));

        given().when().get("/api/v1/rides/by-bike-type")
                .then().statusCode(200).body("category", hasSize(2));

        given().when().get("/api/v1/rides/by-rider-type")
                .then().statusCode(200).body("category", hasSize(2));

        given().when().get("/api/v1/rides/activity?groupBy=dayOfWeek")
                .then().statusCode(200).body("size()", equalTo(2));

        given().when().get("/api/v1/stations?direction=start&limit=5")
                .then().statusCode(200)
                .body("items", hasSize(2))
                .body("total", greaterThan(0));

        given().when().get("/api/v1/routes?limit=5")
                .then().statusCode(200)
                .body("items", hasSize(2))
                .body("total", greaterThan(0));

        given().when().get("/api/v1/stations/imbalance?limit=5")
                .then().statusCode(200)
                .body("items", hasSize(3))
                .body("total", greaterThan(0));
    }

    @Test
    void reportsCsvProvenanceAndDataQuality() {
        given()
                .when().get("/api/v1/data-quality")
                .then()
                .statusCode(200)
                .body("filesDiscovered", equalTo(2))
                .body("filesLoaded", equalTo(2))
                .body("totalRows", equalTo(2))
                .body("sources[0].filename", equalTo("part-a.csv"))
                .body("sources[0].rowsLoaded", equalTo(1))
                .body("missingValues.startStationName", equalTo(0))
                .body("missingValues.endStationName", equalTo(0))
                .body("duplicateRideIds", equalTo(0))
                .body("invalidDurations", equalTo(0))
                .body("ridesOver24Hours", equalTo(0));
    }

    @Test
    void appliesFiltersAndPagination() {
        given()
                .queryParam("riderType", "casual")
                .queryParam("bikeType", "electric_bike")
                .when().get("/api/v1/rides/summary")
                .then().statusCode(200)
                .body("rideCount", greaterThan(0));

        given()
                .queryParam("limit", 1)
                .queryParam("offset", 1)
                .queryParam("sort", "averageDuration")
                .queryParam("order", "asc")
                .when().get("/api/v1/stations")
                .then().statusCode(200)
                .body("items", hasSize(1))
                .body("limit", equalTo(1))
                .body("offset", equalTo(1));
    }

    @Test
    void rejectsInvalidAndInjectionShapedInputs() {
        given()
                .queryParam("riderType", "' OR 1=1 --")
                .when().get("/api/v1/rides/summary")
                .then().statusCode(400)
                .body("code", equalTo("invalid_request"));

        given()
                .queryParam("sort", "ride_count; DROP TABLE rides")
                .when().get("/api/v1/stations")
                .then().statusCode(400)
                .body("code", equalTo("invalid_request"));

        given()
                .queryParam("from", "2023-07-01")
                .queryParam("to", "2023-06-01")
                .when().get("/api/v1/rides/summary")
                .then().statusCode(400)
                .body("message", equalTo("from must not be after to"));

        given()
                .queryParam("limit", 101)
                .when().get("/api/v1/routes")
                .then().statusCode(400)
                .body("code", equalTo("invalid_request"));
    }

    @Test
    void exposesReadinessAndMetrics() {
        given()
                .when().get("/q/health/ready")
                .then().statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks[0].data.rowsLoaded", equalTo(2));

        given()
                .when().get("/q/metrics")
                .then().statusCode(200)
                .body(containsString("citibike_csv_rows"))
                .body(containsString("http_server_requests"));
    }

    @Test
    void unknownEndpointIsStillA404() {
        given()
                .when().get("/api/v1/not-a-resource")
                .then().statusCode(404)
                .body("code", is(notNullValue()));
    }
    @Test
    void everyEndpointHasValidDefaultsAndEmptyResults() {
        for (String endpoint : java.util.List.of("/rides/summary", "/rides/timeseries", "/rides/by-bike-type",
                "/rides/by-rider-type", "/rides/activity", "/stations", "/routes", "/stations/imbalance", "/data-quality")) {
            given().when().get("/api/v1" + endpoint).then().statusCode(200);
        }
        given().queryParam("from", "2024-01-01").queryParam("to", "2024-01-01")
                .when().get("/api/v1/rides/summary").then().statusCode(200)
                .body("rideCount", equalTo(0)).body("roundTripCount", equalTo(0))
                .body("averageDurationSeconds", org.hamcrest.Matchers.nullValue());
        for (String endpoint : java.util.List.of("/rides/timeseries", "/rides/by-bike-type", "/rides/by-rider-type", "/rides/activity")) {
            given().queryParam("from", "2024-01-01").queryParam("to", "2024-01-01")
                    .when().get("/api/v1" + endpoint).then().statusCode(200).body("size()", equalTo(0));
        }
        for (String endpoint : java.util.List.of("/stations", "/routes", "/stations/imbalance")) {
            given().queryParam("from", "2024-01-01").queryParam("to", "2024-01-01")
                    .when().get("/api/v1" + endpoint).then().statusCode(200)
                    .body("items", hasSize(0)).body("total", equalTo(0));
        }
    }

    @Test
    void paginationTotalsRemainAccurateBeyondLastPageAndOrderIsStable() {
        for (String endpoint : java.util.List.of("/stations", "/routes", "/stations/imbalance")) {
            int total = given().when().get("/api/v1" + endpoint).then().statusCode(200).extract().path("total");
            given().queryParam("offset", 10000).when().get("/api/v1" + endpoint).then().statusCode(200)
                    .body("items", hasSize(0)).body("total", equalTo(total));
            var first = given().queryParam("limit", 100).when().get("/api/v1" + endpoint).then().extract().jsonPath().getList("items");
            var second = given().queryParam("limit", 100).when().get("/api/v1" + endpoint).then().extract().jsonPath().getList("items");
            org.junit.jupiter.api.Assertions.assertEquals(first, second);
        }
    }

    @Test
    void rejectsInvalidDatesEnumsAndPaginationWithStable400() {
        for (var parameter : java.util.List.of(
                java.util.Map.entry("from", "2023-02-29"), java.util.Map.entry("from", "+2023-06-01"),
                java.util.Map.entry("from", "2023-6-1"), java.util.Map.entry("from", "' OR 1=1 --"),
                java.util.Map.entry("riderType", "unknown"), java.util.Map.entry("bikeType", "unknown"))) {
            given().queryParam(parameter.getKey(), parameter.getValue()).when().get("/api/v1/rides/summary")
                    .then().statusCode(400).body("code", equalTo("invalid_request"));
        }
        given().queryParam("from", "2023-01-01").queryParam("to", "2024-01-02")
                .when().get("/api/v1/rides/summary").then().statusCode(400);
        given().queryParam("from", "2023-01-01").queryParam("to", "2024-01-01")
                .when().get("/api/v1/rides/summary").then().statusCode(200);
        for (var parameter : java.util.List.of(java.util.Map.entry("direction", "unknown"),
                java.util.Map.entry("sort", "unknown"), java.util.Map.entry("order", "unknown"),
                java.util.Map.entry("limit", "0"), java.util.Map.entry("limit", "101"),
                java.util.Map.entry("limit", "not-an-integer"), java.util.Map.entry("limit", "2147483648"),
                java.util.Map.entry("offset", "-1"), java.util.Map.entry("offset", "10001"),
                java.util.Map.entry("offset", "9999999999999999999"))) {
            given().queryParam(parameter.getKey(), parameter.getValue()).when().get("/api/v1/stations")
                    .then().statusCode(400).body("code", equalTo("invalid_request"));
        }
        given().queryParam("interval", "unknown").when().get("/api/v1/rides/timeseries").then().statusCode(400);
        given().queryParam("groupBy", "unknown").when().get("/api/v1/rides/activity").then().statusCode(400);
        given().queryParam("includeRoundTrips", "maybe").when().get("/api/v1/routes").then().statusCode(400);
    }

    @Test
    void stationSearchBindsMaliciousTextAndRejectsOversizedNamesWithoutLoggingIt() {
        String malicious = "LEAK_PROBE' OR 1=1 --";
        try (var logs = new com.bscllc.learning.citibike.support.LogCapture()) {
            given().queryParam("stationName", malicious).when().get("/api/v1/stations")
                    .then().statusCode(200).body("items", hasSize(0)).body("total", equalTo(0));
            given().queryParam("stationName", "x".repeat(201)).when().get("/api/v1/stations")
                    .then().statusCode(400);
            given().queryParam("stationName", "LEAK_PROBE\nmalicious").when().get("/api/v1/stations")
                    .then().statusCode(400);
            given().queryParam("sort", malicious).when().get("/api/v1/stations").then().statusCode(400);
            given().when().get("/api/v1/stations?sort=rideCount%3B%20DROP%20TABLE%20rides").then().statusCode(400);
            given().when().get("/api/v1/rides/summary").then().statusCode(200).body("rideCount", equalTo(2));
            org.junit.jupiter.api.Assertions.assertFalse(logs.content().contains("LEAK_PROBE"));
            org.junit.jupiter.api.Assertions.assertFalse(logs.content().contains("SELECT "));
        }
        String name = given().when().get("/api/v1/stations").then().extract().path("items[0].stationName");
        given().queryParam("stationName", name).when().get("/api/v1/stations").then().statusCode(200)
                .body("items.stationName", org.hamcrest.Matchers.everyItem(equalTo(name)));
    }

    @Test
    void saturationReturns503WithRetryAfterAndRecovers() {
        holdConnections(config.jdbc().maxConnections());
        given().when().get("/api/v1/rides/summary").then().statusCode(200);
    }

    private void holdConnections(int remaining) {
        if (remaining == 0) {
            given().when().get("/api/v1/rides/summary").then().statusCode(503)
                    .header("Retry-After", "1").body("code", equalTo("database_unavailable"));
            given().when().get("/q/health/ready").then().statusCode(503).body("status", equalTo("DOWN"));
            given().when().get("/q/health/live").then().statusCode(200).body("status", equalTo("UP"));
            return;
        }
        manager.withConnection(connection -> { holdConnections(remaining - 1); return null; });
    }

    @Test
    void metricsContainLoaderQueryAndHttpMetersWithBoundedTags() {
        given().when().get("/api/v1/rides/summary").then().statusCode(200);
        given().when().get("/q/metrics").then().statusCode(200)
                .body(containsString("citibike_jdbc_query_duration"))
                .body(containsString("citibike_csv_load_duration"))
                .body(containsString("citibike_csv_files_discovered"))
                .body(containsString("citibike_jdbc_connections_active"))
                .body(containsString("http_server_requests"));
        for (var meter : registry.getMeters()) {
            if (!meter.getId().getName().startsWith("citibike.")) continue;
            for (var tag : meter.getId().getTags()) {
                org.junit.jupiter.api.Assertions.assertTrue(java.util.Set.of("operation", "outcome", "category", "reason").contains(tag.getKey()));
                org.junit.jupiter.api.Assertions.assertTrue(java.util.Set.of("summary", "timeseries", "by-bike-type", "by-rider-type", "activity", "stations", "routes", "station-imbalance", "data-quality", "success", "failure", "capacity", "sql", "timeout", "runtime", "validation", "succeeded", "unchanged", "failed", "busy", "no_match", "committed", "waiting", "overdue").contains(tag.getValue()));
            }
        }
        given().when().get("/q/health/live").then().statusCode(200).body("status", equalTo("UP"));
        given().when().get("/api/v1/data-quality").then().statusCode(200).body("invalidRows", equalTo(0));
    }

    @Test
    void unexpectedFailuresAreGenericAndLogsHaveCorrelationWithoutSensitiveDetails() {
        try (var logs = new com.bscllc.learning.citibike.support.LogCapture()) {
            for (String category : java.util.List.of("jdbc", "unexpected")) {
                var response = given().when().get("/test/failure/" + category).then().statusCode(500)
                        .header("X-Correlation-ID", org.hamcrest.Matchers.matchesPattern("[a-f0-9-]{36}"))
                        .body("code", equalTo("internal_error"))
                        .body("message", equalTo("The request could not be completed")).extract().response();
                org.junit.jupiter.api.Assertions.assertFalse(response.asString().contains("LEAK_PROBE"));
                org.junit.jupiter.api.Assertions.assertTrue(logs.content().contains(response.header("X-Correlation-ID")));
            }
            org.junit.jupiter.api.Assertions.assertFalse(logs.content().contains("LEAK_PROBE"));
            org.junit.jupiter.api.Assertions.assertFalse(logs.content().contains("SQLException"));
        }
    }

    @Test
    void allStructuralAllowlistChoicesExecuteCorrectly() {
        for (String direction : java.util.List.of("start", "end")) {
            for (String sort : java.util.List.of("rideCount", "averageDuration")) {
                for (String order : java.util.List.of("asc", "desc")) {
                    given().queryParam("direction", direction).queryParam("sort", sort).queryParam("order", order)
                            .when().get("/api/v1/stations").then().statusCode(200);
                    given().queryParam("sort", sort).queryParam("order", order).queryParam("includeRoundTrips", true)
                            .when().get("/api/v1/routes").then().statusCode(200);
                }
            }
        }
        for (String interval : java.util.List.of("hour", "day")) {
            var response = given().queryParam("interval", interval).when().get("/api/v1/rides/timeseries").then().statusCode(200).extract().jsonPath();
            org.junit.jupiter.api.Assertions.assertEquals(2, response.<Integer>getList("rideCount").stream().mapToInt(Integer::intValue).sum());
        }
        for (String group : java.util.List.of("hourOfDay", "dayOfWeek")) {
            var response = given().queryParam("groupBy", group).when().get("/api/v1/rides/activity").then().statusCode(200).extract().jsonPath();
            org.junit.jupiter.api.Assertions.assertEquals(2, response.<Integer>getList("rideCount").stream().mapToInt(Integer::intValue).sum());
        }
        given().queryParam("riderType", "member").when().get("/api/v1/rides/summary").then().statusCode(200).body("rideCount", equalTo(1));
        given().queryParam("riderType", "casual").when().get("/api/v1/rides/summary").then().statusCode(200).body("rideCount", equalTo(1));
        for (var category : java.util.Map.of("classic_bike", 1, "electric_bike", 1, "docked_bike", 0).entrySet()) {
            given().queryParam("bikeType", category.getKey()).when().get("/api/v1/rides/summary").then().statusCode(200).body("rideCount", equalTo(category.getValue()));
        }
    }

    @Test
    void monitoringExportsLatencyBucketsReadinessAndConfiguredLimits() {
        given().when().get("/api/v1/rides/summary").then().statusCode(200);
        String exported = given().when().get("/q/metrics").then().statusCode(200).extract().asString();
        for (String name : java.util.List.of("http_server_requests_seconds_bucket",
                "citibike_jdbc_query_duration_seconds_bucket", "citibike_analytics_ready",
                "citibike_duckdb_memory_limit_bytes", "citibike_duckdb_spill_limit_bytes",
                "citibike_jdbc_connections_limit", "citibike_download_enabled",
                "citibike_download_last_success_timestamp_seconds")) {
            org.junit.jupiter.api.Assertions.assertTrue(exported.contains(name), name);
        }
        org.junit.jupiter.api.Assertions.assertEquals(1, registry.get("citibike.analytics.ready").gauge().value());
        org.junit.jupiter.api.Assertions.assertEquals(4, registry.get("citibike.jdbc.connections.limit").gauge().value());
        org.junit.jupiter.api.Assertions.assertEquals(512000000, registry.get("citibike.duckdb.memory.limit.bytes").gauge().value());
        org.junit.jupiter.api.Assertions.assertEquals(0, registry.get("citibike.download.enabled").gauge().value());
        org.junit.jupiter.api.Assertions.assertTrue(Double.isNaN(registry.get("citibike.download.last.success.timestamp.seconds").gauge().value()));
    }

}

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
class RideAnalyticsResourceTest {
    @Test
    void returnsRideSummaryForTheLoadedResources() {
        given()
                .when().get("/api/v1/rides/summary")
                .then()
                .statusCode(200)
                .body("from", equalTo("2023-06-01"))
                .body("to", equalTo("2023-06-30"))
                .body("rideCount", equalTo(1500))
                .body("medianDurationSeconds", equalTo(654.0f))
                .body("ridesOver24Hours", equalTo(22));
    }

    @Test
    void supportsAllAnalyticalQueryFamilies() {
        given().when().get("/api/v1/rides/timeseries?interval=day")
                .then().statusCode(200).body("size()", equalTo(30));

        given().when().get("/api/v1/rides/by-bike-type")
                .then().statusCode(200).body("category", hasSize(3));

        given().when().get("/api/v1/rides/by-rider-type")
                .then().statusCode(200).body("category", hasSize(2));

        given().when().get("/api/v1/rides/activity?groupBy=dayOfWeek")
                .then().statusCode(200).body("size()", equalTo(7));

        given().when().get("/api/v1/stations?direction=start&limit=5")
                .then().statusCode(200)
                .body("items", hasSize(5))
                .body("total", greaterThan(0));

        given().when().get("/api/v1/routes?limit=5")
                .then().statusCode(200)
                .body("items", hasSize(5))
                .body("total", greaterThan(0));

        given().when().get("/api/v1/stations/imbalance?limit=5")
                .then().statusCode(200)
                .body("items", hasSize(5))
                .body("total", greaterThan(0));
    }

    @Test
    void reportsCsvProvenanceAndDataQuality() {
        given()
                .when().get("/api/v1/data-quality")
                .then()
                .statusCode(200)
                .body("filesDiscovered", equalTo(1))
                .body("filesLoaded", equalTo(1))
                .body("totalRows", equalTo(1500))
                .body("sources[0].filename", equalTo("citibike.csv"))
                .body("sources[0].rowsLoaded", equalTo(1500))
                .body("missingValues.startStationName", equalTo(11))
                .body("missingValues.endStationName", equalTo(64))
                .body("duplicateRideIds", equalTo(0))
                .body("invalidDurations", equalTo(0))
                .body("ridesOver24Hours", equalTo(22));
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
                .queryParam("limit", 2)
                .queryParam("offset", 2)
                .queryParam("sort", "averageDuration")
                .queryParam("order", "asc")
                .when().get("/api/v1/stations")
                .then().statusCode(200)
                .body("items", hasSize(2))
                .body("limit", equalTo(2))
                .body("offset", equalTo(2));
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
                .body("checks[0].data.rowsLoaded", equalTo(1500));

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
}

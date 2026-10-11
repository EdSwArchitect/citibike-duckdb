package com.bscllc.learning.citibike;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class RealResourceSmokeTest {
    @Test void loadsRealResourceSetAndExposesHealthyBaseline() {
        given().when().get("/api/v1/rides/summary").then().statusCode(200)
                .body("rideCount", equalTo(1500)).body("medianDurationSeconds", equalTo(654.0f))
                .body("ridesOver24Hours", equalTo(22)).body("p90DurationSeconds", notNullValue());
        given().when().get("/api/v1/data-quality").then().statusCode(200)
                .body("filesLoaded", equalTo(1)).body("totalRows", equalTo(1500))
                .body("sources[0].filename", equalTo("citibike.csv"))
                .body("duplicateRideIds", equalTo(0)).body("invalidRows", equalTo(0));
        given().when().get("/api/v1/rides/by-bike-type").then().statusCode(200)
                .body("category", containsInAnyOrder("classic_bike", "electric_bike", "docked_bike"));
        given().when().get("/q/health/ready").then().statusCode(200).body("status", equalTo("UP"));
    }
}

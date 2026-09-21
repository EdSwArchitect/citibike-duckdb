package com.bscllc.learning.citibike;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(MultiFileProfile.class)
class MultiFileLoadingTest {
    @Test
    void combinesEveryCsvMatchingTheResourcePattern() {
        given()
                .when().get("/api/v1/rides/summary")
                .then().statusCode(200)
                .body("rideCount", equalTo(2));

        given()
                .when().get("/api/v1/data-quality")
                .then().statusCode(200)
                .body("filesDiscovered", equalTo(2))
                .body("filesLoaded", equalTo(2))
                .body("totalRows", equalTo(2))
                .body("sources.filename", containsInAnyOrder("part-a.csv", "part-b.csv"));
    }
}

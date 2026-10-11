package com.bscllc.learning.citibike;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusIntegrationTest
class PackagedApplicationIT {
    @Test void fastJarLoadsOnlyApplicationResourcesAndServesTheApi() {
        given().when().get("/api/v1/rides/summary").then().statusCode(200).body("rideCount", equalTo(1500));
        given().when().get("/api/v1/data-quality").then().statusCode(200)
                .body("sources.filename", contains("citibike.csv")).body("filesLoaded", equalTo(1));
        given().when().get("/q/health/ready").then().statusCode(200).body("status", equalTo("UP"));
        given().when().get("/q/health/live").then().statusCode(200).body("status", equalTo("UP"));
        given().when().get("/q/metrics").then().statusCode(200).body(containsString("citibike_csv_rows"));
        given().when().get("/api/v1/download/status").then().statusCode(200)
                .body("outcome", equalTo("DISABLED"));
        given().when().post("/api/v1/download/status").then().statusCode(405);
        given().when().get("/test/failure/jdbc").then().statusCode(404);
    }
}

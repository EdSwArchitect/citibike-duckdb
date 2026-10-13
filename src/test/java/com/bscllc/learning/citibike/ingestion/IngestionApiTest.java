package com.bscllc.learning.citibike.ingestion;

import io.quarkus.test.junit.*;
import io.quarkus.test.common.QuarkusTestResource;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import java.util.*;

@QuarkusTest
@TestProfile(IngestionApiTest.Profile.class)
@QuarkusTestResource(value=IngestionApiResource.class,restrictToAnnotatedClass=true)
class IngestionApiTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){return Map.ofEntries(
            Map.entry("citibike.storage.mode","file"),Map.entry("citibike.ingestion.enabled","true"),
            Map.entry("citibike.ingestion.admin-enabled","true"),Map.entry("citibike.ingestion.check-on-startup","false"),
            Map.entry("citibike.ingestion.monthly-cron","off"),Map.entry("citibike.ingestion.catch-up-cron","off"),
            Map.entry("citibike.ingestion.min-free-disk-bytes","0"),Map.entry("citibike.ingestion.max-expanded-bytes","1048576"),
            Map.entry("quarkus.security.users.embedded.enabled","true"),Map.entry("quarkus.security.users.embedded.plain-text","true"),
            Map.entry("quarkus.security.users.embedded.users.ingest","fixture-password"),Map.entry("quarkus.security.users.embedded.roles.ingest","ingestion-admin"),
            Map.entry("quarkus.security.users.embedded.users.viewer","fixture-password"),Map.entry("quarkus.security.users.embedded.roles.viewer","viewer"));}
    }
    @Test void protectedOperationsInitialReadinessAndSuccessfulImport()throws Exception {
        given().when().get("/api/v1/admin/ingestion/status").then().statusCode(401);
        given().auth().preemptive().basic("viewer","fixture-password").when().get("/api/v1/admin/ingestion/status").then().statusCode(403);
        given().when().get("/q/health/ready").then().statusCode(503).body("status",equalTo("DOWN"));
        given().when().get("/q/health/live").then().statusCode(200);
        given().auth().preemptive().basic("ingest","fixture-password").contentType("application/json").body("{\"month\":\"2023-07\"}")
            .when().post("/api/v1/admin/ingestion/months").then().statusCode(202);
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<deadline){if(given().when().get("/q/health/ready").statusCode()==200)break;Thread.sleep(50);}
        given().when().get("/api/v1/rides/summary").then().statusCode(200).body("rideCount",equalTo(1));
        given().auth().preemptive().basic("ingest","fixture-password").when().get("/api/v1/admin/ingestion/status").then().statusCode(200).body("coverage[0].month",equalTo("2023-07"));
        given().when().get("/api/v1/data-quality").then().statusCode(200).body("totalRows",equalTo(1)).body("invalidRows",equalTo(0));
        given().auth().preemptive().basic("ingest","fixture-password").contentType("application/json").body("{\"month\":\"9999-01\"}")
            .when().post("/api/v1/admin/ingestion/months").then().statusCode(400).body("code",equalTo("invalid_request"));
        given().auth().preemptive().basic("ingest","fixture-password").queryParam("limit",1000).when().get("/api/v1/admin/ingestion/jobs").then().statusCode(400);
        given().auth().preemptive().basic("ingest","fixture-password").contentType("application/json").body("{\"from\":\"2020-01\",\"to\":\"2023-07\"}")
            .when().post("/api/v1/admin/ingestion/backfills").then().statusCode(400);
    }
}

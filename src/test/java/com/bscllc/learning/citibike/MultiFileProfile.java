package com.bscllc.learning.citibike;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class MultiFileProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("citibike.csv.resource-pattern", "part-*.csv");
    }
}

package com.bscllc.learning.citibike;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class MultiFileProfile implements QuarkusTestProfile {
    @Override
    public java.util.Set<Class<?>> getEnabledAlternatives() {
        return java.util.Set.of(com.bscllc.learning.citibike.db.TestCsvResourceCatalog.class);
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("citibike.csv.resource-pattern", "part-*.csv");
    }
}

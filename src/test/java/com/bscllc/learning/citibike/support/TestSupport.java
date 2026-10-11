package com.bscllc.learning.citibike.support;

import com.bscllc.learning.citibike.config.CitibikeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;

public final class TestSupport {
    public static final String HEADER = "ride_id,rideable_type,started_at,ended_at,start_station_name,start_station_id,end_station_name,end_station_id,start_lat,start_lng,end_lat,end_lng,member_casual\n";
    public static final String ROW = "R1,classic_bike,2023-07-01 08:00:00,2023-07-01 08:10:00,Station A,001.020,Station B,002.030,40.7,-73.9,40.71,-73.91,member\n";

    private TestSupport() {}

    public static CitibikeConfig config(Map<String, String> overrides) {
        SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder().withMapping(CitibikeConfig.class)
                .withConverter(java.time.Duration.class, 100, new io.quarkus.runtime.configuration.DurationConverter());
        overrides.forEach(builder::withDefaultValue);
        return builder.build().getConfigMapping(CitibikeConfig.class);
    }
}

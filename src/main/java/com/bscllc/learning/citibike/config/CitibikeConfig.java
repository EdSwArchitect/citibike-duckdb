package com.bscllc.learning.citibike.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

@ConfigMapping(prefix = "citibike")
public interface CitibikeConfig {
    Csv csv();

    Jdbc jdbc();

    DuckDb duckdb();

    Api api();

    interface Csv {
        @WithDefault("*.csv")
        String resourcePattern();

        @WithDefault("true")
        boolean failOnEmpty();

        @WithDefault("true")
        boolean failOnDuplicateRideId();
    }

    interface Jdbc {
        @WithDefault("2s")
        Duration queryTimeout();

        @WithDefault("500ms")
        Duration acquireTimeout();

        @WithDefault("4")
        int maxConnections();
    }

    interface DuckDb {
        @WithDefault("4")
        int threads();

        @WithDefault("512MB")
        String memoryLimit();

        @WithDefault("512MB")
        String maxTempDirectorySize();
    }

    interface Api {
        @WithDefault("366")
        int maxDateRangeDays();

        @WithDefault("100")
        int maxPageSize();

        @WithDefault("10000")
        int maxOffset();
    }
}

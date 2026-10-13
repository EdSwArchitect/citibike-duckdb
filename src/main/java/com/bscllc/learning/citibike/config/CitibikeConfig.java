package com.bscllc.learning.citibike.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;

@ConfigMapping(prefix = "citibike")
public interface CitibikeConfig {
    Storage storage();

    interface Storage {
        @WithDefault("file") @Pattern(regexp="memory|file") String mode();
        @WithDefault("./data/citibike") String dataDirectory();
        @WithDefault("citibike.duckdb") @Pattern(regexp="[A-Za-z0-9_-]+\\.duckdb") String databasePath();
        @WithDefault("false") boolean bootstrapBundled();
    }

    Csv csv();

    Jdbc jdbc();

    DuckDb duckdb();

    Api api();

    interface Csv {
        @WithDefault("*.csv")
        @Pattern(regexp = "[^/\\\\]+")
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
        @Min(1)
        @Max(32)
        int maxConnections();
    }

    interface DuckDb {
        @WithDefault("4")
        @Min(1)
        @Max(32)
        int threads();

        @WithDefault("512MB")
        @Pattern(regexp = "[1-9][0-9]*(KB|MB|GB)")
        String memoryLimit();

        @WithDefault("512MB")
        @Pattern(regexp = "[1-9][0-9]*(KB|MB|GB)")
        String maxTempDirectorySize();
    }

    interface Api {
        @WithDefault("366")
        @Min(1)
        @Max(366)
        int maxDateRangeDays();

        @WithDefault("100")
        @Min(1)
        @Max(100)
        int maxPageSize();

        @WithDefault("10000")
        @Min(0)
        @Max(1000000)
        int maxOffset();
    }
}

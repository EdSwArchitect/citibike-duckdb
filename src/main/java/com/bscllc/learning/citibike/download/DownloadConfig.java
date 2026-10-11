package com.bscllc.learning.citibike.download;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;

@ConfigMapping(prefix = "citibike.download")
public interface DownloadConfig {
    @WithDefault("false") boolean enabled();
    @WithDefault("./data/citibike-downloads") String dataDirectory();
    @WithDefault("0 0 4 * * ?") String schedule();
    @WithDefault("America/New_York") String timeZone();
    @WithDefault("true") boolean checkOnStartup();
    @WithDefault("10s") Duration connectTimeout();
    @WithDefault("60s") Duration readTimeout();
    @WithDefault("45m") Duration operationTimeout();
    @WithDefault("3") int maxAttempts();
    @WithDefault("60s") Duration maxBackoff();
    @WithDefault("2") int maxRediscoveries();
    @WithDefault("4294967296") long maxDownloadBytes();
    @WithDefault("100") int maxListingPages();
    @WithDefault("4194304") long maxListingResponseBytes();
    @WithDefault("8589934592") long minFreeDiskBytes();
    @WithDefault("2") int retainedGenerations();
    @WithDefault("100") int retainedAttempts();
    @WithDefault("24h") Duration cleanupGracePeriod();
}

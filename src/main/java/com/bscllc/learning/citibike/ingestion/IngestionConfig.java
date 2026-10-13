package com.bscllc.learning.citibike.ingestion;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.net.URI;
import java.util.Optional;

@ConfigMapping(prefix="citibike.ingestion")
public interface IngestionConfig {
    @WithDefault("true") boolean enabled();
    @WithDefault("false") boolean adminEnabled();
    @WithDefault("false") boolean includeJerseyCity();
    @WithDefault("https://s3.amazonaws.com/tripdata") URI sourceUri();
    @WithDefault("0 0 4 6 * ?") String monthlyCron();
    @WithDefault("0 30 4 * * ?") String catchUpCron();
    @WithDefault("America/New_York") String timeZone();
    @WithDefault("true") boolean checkOnStartup();
    @WithDefault("3") int lookbackMonths();
    Optional<String> catchUpStartMonth();
    Backfill backfill();
    interface Backfill { Optional<String> from(); Optional<String> to(); }
    @WithDefault("45") int publicationWaitDays();
    @WithDefault("2") int jobsPerRun();
    @WithDefault("24") int maxBackfillMonths();
    @WithDefault("100") int maxQueuedJobs();
    @WithDefault("10s") Duration connectTimeout();
    @WithDefault("60s") Duration readTimeout();
    @WithDefault("30m") Duration downloadTimeout();
    @WithDefault("3") int maxAttempts();
    @WithDefault("60s") Duration maxBackoff();
    @WithDefault("2") int maxRediscoveries();
    @WithDefault("4294967296") long maxDownloadBytes();
    @WithDefault("4294967296") long maxEntryBytes();
    @WithDefault("17179869184") long maxExpandedBytes();
    @WithDefault("1000") int maxEntries();
    @WithDefault("2") int maxZipLayers();
    @WithDefault("100") double maxCompressionRatio();
    @WithDefault("100") int maxListingPages();
    @WithDefault("4194304") long maxListingResponseBytes();
    @WithDefault("100000") int maxDiscoveredObjects();
    @WithDefault("30m") Duration sqlTimeout();
    @WithDefault("2h") Duration jobTimeout();
    @WithDefault("68719476736") long cacheQuotaBytes();
    @WithDefault("30d") Duration supersededRetention();
    @WithDefault("8589934592") long minFreeDiskBytes();
}

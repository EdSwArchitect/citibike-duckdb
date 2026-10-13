package com.bscllc.learning.citibike.ingestion;

import com.bscllc.learning.citibike.download.DownloadConfig;
import java.time.Duration;

/** Reuses the tested streaming transport without changing latest-only downloader semantics. */
public record IngestionHttpLimits(IngestionConfig config) implements DownloadConfig {
    public boolean enabled(){return true;}
    public String dataDirectory(){return "unused";}
    public String schedule(){return "off";}
    public String timeZone(){return config.timeZone();}
    public boolean checkOnStartup(){return false;}
    public Duration connectTimeout(){return config.connectTimeout();}
    public Duration readTimeout(){return config.readTimeout();}
    public Duration operationTimeout(){return config.downloadTimeout().compareTo(config.jobTimeout())<0?config.downloadTimeout():config.jobTimeout();}
    public int maxAttempts(){return config.maxAttempts();}
    public Duration maxBackoff(){return config.maxBackoff();}
    public int maxRediscoveries(){return config.maxRediscoveries();}
    public long maxDownloadBytes(){return config.maxDownloadBytes();}
    public int maxListingPages(){return config.maxListingPages();}
    public long maxListingResponseBytes(){return config.maxListingResponseBytes();}
    public long minFreeDiskBytes(){return config.minFreeDiskBytes();}
    public int retainedGenerations(){return 2;}
    public int retainedAttempts(){return 100;}
    public Duration cleanupGracePeriod(){return Duration.ofDays(30);}
}

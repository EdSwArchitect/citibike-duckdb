package com.bscllc.learning.citibike.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@io.quarkus.runtime.Startup
@ApplicationScoped
public class DownloadCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(DownloadCoordinator.class);
    @Inject DownloadConfig config;
    @Inject ObjectMapper json;
    @Inject MeterRegistry metrics;
    private final ReentrantLock running = new ReentrantLock();
    public record Status(String outcome, String reason, String lastSuccessfulMonth, String lastSuccessfulAt, String currentAttempt) {}
    private volatile Status status = new Status("DISABLED",null,null,null,null);
    private volatile double lastCheckTimestamp = Double.NaN;
    private volatile double lastCheckSuccessful = Double.NaN;
    @PostConstruct
    void registerMonitoring() {
        Gauge.builder("citibike.download.enabled", this, c -> c.config.enabled() ? 1 : 0).register(metrics);
        Gauge.builder("citibike.download.active", this, c -> c.running.isLocked() ? 1 : 0).register(metrics);
        Gauge.builder("citibike.download.last.check.timestamp.seconds", this, c -> c.lastCheckTimestamp).register(metrics);
        Gauge.builder("citibike.download.last.check.successful", this, c -> c.lastCheckSuccessful).register(metrics);
        Gauge.builder("citibike.download.last.success.timestamp.seconds", this, c -> {
            String timestamp = c.status.lastSuccessfulAt();
            return timestamp == null ? Double.NaN : Instant.parse(timestamp).getEpochSecond();
        }).register(metrics);
        for (String outcome : java.util.List.of("succeeded", "unchanged", "failed", "busy", "no_match"))
            metrics.counter("citibike.download.checks", "outcome", outcome);
    }
    public Status status() {
        if (config!=null && config.enabled() && status.outcome().equals("DISABLED"))
            return new Status("IDLE",null,null,null,null);
        return status;
    }
    /** Internal manual trigger: no user-supplied URL, month, or archive path. */
    public String checkLatest() {
        if (!config.enabled()) return "DISABLED";
        return execute(new LatestArchiveDownloader(config));
    }
    String execute(LatestArchiveDownloader http) {
        if (!running.tryLock()) {
            if (metrics!=null) metrics.counter("citibike.download.checks","outcome","busy").increment();
            return "BUSY";
        }
        String id=UUID.randomUUID().toString(), started=Instant.now().toString(), key=null;
        DownloadStateStore store=null;
        try {
            validateConfig();
            store=new DownloadStateStore(Path.of(config.dataDirectory()),json);
            if (Files.isSymbolicLink(store.root().resolve("owner.lock"))) throw new IOException("UNSAFE_DIRECTORY");
            try (FileChannel channel=FileChannel.open(store.root().resolve("owner.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
                FileLock lock;
                try { lock=channel.tryLock(); } catch (OverlappingFileLockException e) { lock=null; }
                if (lock==null) return record("BUSY","LOCKED",store);
                try (FileLock held=lock) {
                    store.recover();
                    store.attempt(new DownloadStateStore.Attempt(id,null,started,null,"DISCOVERING",null,0));
                    status=new Status("DISCOVERING",null,status.lastSuccessfulMonth(),status.lastSuccessfulAt(),id);
                    http.begin();
                    for (int cycle=0;cycle<=config.maxRediscoveries();cycle++) {
                        try {
                            var selected=http.discover();
                            if (selected==null) {
                                store.attempt(new DownloadStateStore.Attempt(id,null,started,Instant.now().toString(),"NO_MATCH",null,http.retries()));
                                store.cleanup(config); return record("NO_MATCH",null,store);
                            }
                            var source=http.head(selected); key=source.key();
                            DownloadStateStore.Manifest active;
                            try { active=store.active(); } catch (IOException e) { active=null; }
                            boolean valid;
                            try { valid=store.valid(active); } catch (IOException invalid) { valid=false; }
                            if (valid && active.key().equals(source.key()) && active.size()==source.size()
                                    && (source.version()!=null || source.etag()!=null)
                                    && java.util.Objects.equals(active.version(),source.version())
                                    && java.util.Objects.equals(active.etag(),source.etag())) {
                                store.attempt(new DownloadStateStore.Attempt(id,key,started,Instant.now().toString(),"UNCHANGED",null,http.retries()));
                                store.cleanup(config); return record("UNCHANGED",null,store);
                            }
                            store.attempt(new DownloadStateStore.Attempt(id,key,started,null,"DOWNLOADING",null,http.retries()));
                            status=new Status("DOWNLOADING",null,status.lastSuccessfulMonth(),status.lastSuccessfulAt(),id);
                            var payload=http.download(source,store.root());
                            String archive="archive-"+id+".zip";
                            if (valid && active.checksum().equals(payload.checksum())) {
                                Files.delete(payload.path()); archive=active.archive();
                            } else {
                                Files.move(payload.path(),store.root().resolve(archive),StandardCopyOption.ATOMIC_MOVE); store.syncDirectory();
                            }
                            String completed=Instant.now().toString();
                            store.publish(new DownloadStateStore.Manifest(id,key,source.month(),source.etag(),source.version(),source.modified(),payload.checksum(),payload.size(),archive,started,completed,completed,"SUCCEEDED"));
                            store.attempt(new DownloadStateStore.Attempt(id,key,started,completed,"SUCCEEDED",null,http.retries()));
                            store.cleanup(config); return record("SUCCEEDED",null,store);
                        } catch (LatestArchiveDownloader.Changed changed) {
                            if (cycle==config.maxRediscoveries()) throw changed;
                        }
                    }
                } catch (Exception failure) {
                    // Persist a failed attempt while still owning the directory lock.
                    try {
                        var committed=store.active();
                        if (committed!=null && id.equals(committed.attemptId()) && store.valid(committed))
                            return record("SUCCEEDED","POST_COMMIT_CLEANUP",store);
                    } catch (IOException ignored) {}
                    String reason=reason(failure);
                    try { store.attempt(new DownloadStateStore.Attempt(id,key,started,Instant.now().toString(),"FAILED",reason,http.retries())); }
                    catch (IOException ignored) { LOG.warn("Archive download attempt={} state persistence failed",id); }
                    try { store.cleanup(config); } catch (IOException ignored) {}
                    return record("FAILED",reason,store);
                }
            }
            return record("FAILED","SOURCE_CHANGED",store);
        } catch (Exception e) { return record("FAILED",reason(e),store); }
        finally { running.unlock(); }
    }
    private String reason(Exception e) {
        if (e instanceof LatestArchiveDownloader.Permanent || (e instanceof IOException && e.getMessage()!=null && e.getMessage().matches("[A-Z_]+"))) return e.getMessage();
        return "IO_OR_CONFIGURATION";
    }
    private String record(String outcome,String reason,DownloadStateStore store) {
        DownloadStateStore.Manifest active=null;
        if (store!=null) try { active=store.active(); if (!store.valid(active)) active=null; } catch (IOException ignored) {}
        status=new Status(outcome,reason,active==null?null:active.month(),active==null?null:active.completedAt(),null);
        if (!outcome.equals("BUSY")) {
            lastCheckTimestamp = Instant.now().getEpochSecond();
            lastCheckSuccessful = outcome.equals("SUCCEEDED") || outcome.equals("UNCHANGED") ? 1 : 0;
        }
        if (metrics!=null) metrics.counter("citibike.download.checks","outcome",outcome.toLowerCase(java.util.Locale.ROOT)).increment();
        LOG.info("Archive download outcome={} reason={}",outcome,reason);
        return outcome;
    }
    private void validateConfig() {
        if (config.maxAttempts()<1 || config.maxAttempts()>20 || config.maxRediscoveries()<0 || config.maxRediscoveries()>20
            || config.maxListingPages()<1 || config.maxListingResponseBytes()<1 || config.maxListingResponseBytes()>Integer.MAX_VALUE
            || config.maxDownloadBytes()<1 || config.minFreeDiskBytes()<0 || config.retainedAttempts()<1 || config.retainedGenerations()<1
            || config.connectTimeout().isNegative() || config.connectTimeout().isZero()
            || config.readTimeout().isNegative() || config.readTimeout().isZero()
            || config.operationTimeout().isNegative() || config.operationTimeout().isZero()
            || config.maxBackoff().isNegative() || config.cleanupGracePeriod().isNegative()) throw new IllegalArgumentException("Invalid downloader limits");
    }
}

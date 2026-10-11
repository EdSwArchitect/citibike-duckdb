package com.bscllc.learning.citibike.download;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.concurrent.*;

@ApplicationScoped
public class LatestArchiveScheduler {
    @Inject DownloadConfig config;
    @Inject DownloadCoordinator coordinator;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> {
        Thread t=new Thread(r,"citibike-download"); t.setDaemon(true); return t;
    });
    void start(@Observes StartupEvent event) {
        if (config.enabled() && config.checkOnStartup()) worker.submit(coordinator::checkLatest);
    }
    @Scheduled(cron="${citibike.download.schedule:0 0 4 * * ?}", timeZone="${citibike.download.time-zone:America/New_York}", concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    void check() { coordinator.checkLatest(); }
    void stop(@Observes ShutdownEvent event) { worker.shutdownNow(); }
}

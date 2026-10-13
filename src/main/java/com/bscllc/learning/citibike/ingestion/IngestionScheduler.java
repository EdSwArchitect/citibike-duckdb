package com.bscllc.learning.citibike.ingestion;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.LoggerFactory;
@ApplicationScoped
public class IngestionScheduler {
    @Inject IngestionCoordinator coordinator;
    @Scheduled(cron="${citibike.ingestion.monthly-cron:0 0 4 6 * ?}",timeZone="${citibike.ingestion.time-zone:America/New_York}",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    void monthly(){safe(coordinator::monthly);}
    @Scheduled(cron="${citibike.ingestion.catch-up-cron:0 30 4 * * ?}",timeZone="${citibike.ingestion.time-zone:America/New_York}",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    void catchUp(){safe(coordinator::catchUp);}
    // A lightweight queue poll handles requests racing with the end of a worker drain.
    @Scheduled(every="30s",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    void poll(){if(coordinator.enabled())coordinator.kick();}
    private void safe(Runnable trigger){try{trigger.run();}catch(RuntimeException e){LoggerFactory.getLogger(getClass()).warn("Ingestion trigger reason=queue_or_database");}}
}

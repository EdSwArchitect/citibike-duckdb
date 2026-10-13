package com.bscllc.learning.citibike.ingestion;

import io.micrometer.core.instrument.*;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicInteger;

@Startup
@ApplicationScoped
public class IngestionMetrics {
    private final MeterRegistry registry;
    private final AtomicInteger pending=new AtomicInteger();
    private final AtomicInteger running=new AtomicInteger();
    private volatile double lastSuccess=Double.NaN;
    public IngestionMetrics(MeterRegistry registry,IngestionConfig config,com.bscllc.learning.citibike.db.DuckDbManager db) {
        this.registry=registry;
        Gauge.builder("citibike.ingestion.enabled",this,m->config.enabled()&&db.fileMode()?1:0).register(registry);
        Gauge.builder("citibike.ingestion.pending",pending,AtomicInteger::get).register(registry);
        Gauge.builder("citibike.ingestion.running",running,AtomicInteger::get).register(registry);
        Gauge.builder("citibike.ingestion.last.success.timestamp.seconds",this,m->m.lastSuccess).register(registry);
        for(String outcome:java.util.List.of("committed","unchanged","waiting","failed","overdue")) {
            registry.counter("citibike.ingestion.jobs","outcome",outcome);
            registry.timer("citibike.ingestion.duration","outcome",outcome);
        }
        registry.counter("citibike.ingestion.downloaded.bytes");registry.counter("citibike.ingestion.expanded.bytes");
    }
    public void pending(long count){pending.set((int)count);}
    public void running(boolean active){running.set(active?1:0);}
    public void success(java.time.Instant time){lastSuccess=time.getEpochSecond();}
    public void bytes(long downloaded,long expanded){registry.counter("citibike.ingestion.downloaded.bytes").increment(downloaded);registry.counter("citibike.ingestion.expanded.bytes").increment(expanded);}
    public Timer.Sample start(){return Timer.start(registry);}
    public void finished(Timer.Sample timer,String outcome){registry.counter("citibike.ingestion.jobs","outcome",outcome).increment();timer.stop(registry.timer("citibike.ingestion.duration","outcome",outcome));}
}

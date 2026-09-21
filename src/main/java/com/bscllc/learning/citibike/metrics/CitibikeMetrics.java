package com.bscllc.learning.citibike.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class CitibikeMetrics {
    private final MeterRegistry registry;
    private final AtomicInteger filesDiscovered = new AtomicInteger();
    private final AtomicInteger filesLoaded = new AtomicInteger();
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicLong rowsLoaded = new AtomicLong();

    public CitibikeMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("citibike.csv.files.discovered", filesDiscovered, AtomicInteger::get)
                .register(registry);
        Gauge.builder("citibike.csv.files.loaded", filesLoaded, AtomicInteger::get)
                .register(registry);
        Gauge.builder("citibike.csv.rows", rowsLoaded, AtomicLong::get)
                .register(registry);
        Gauge.builder("citibike.jdbc.connections.active", activeConnections, AtomicInteger::get)
                .register(registry);
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void recordLoadSuccess(Timer.Sample sample, int discovered, int loaded, long rows) {
        filesDiscovered.set(discovered);
        filesLoaded.set(loaded);
        rowsLoaded.set(rows);
        sample.stop(registry.timer("citibike.csv.load.duration", "outcome", "success"));
    }

    public void recordLoadFailure(Timer.Sample sample, String reason) {
        sample.stop(registry.timer("citibike.csv.load.duration", "outcome", "failure"));
        Counter.builder("citibike.csv.load.failures")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void recordFileLoad(Timer.Sample sample, boolean success) {
        sample.stop(registry.timer("citibike.csv.file.load.duration",
                "outcome", success ? "success" : "failure"));
    }

    public void connectionAcquired() {
        activeConnections.incrementAndGet();
    }

    public void connectionReleased() {
        activeConnections.decrementAndGet();
    }

    public void recordQuerySuccess(String operation, Timer.Sample sample) {
        sample.stop(registry.timer("citibike.jdbc.query.duration",
                "operation", operation, "outcome", "success"));
    }

    public void recordQueryFailure(String operation, String category, Timer.Sample sample) {
        sample.stop(registry.timer("citibike.jdbc.query.duration",
                "operation", operation, "outcome", "failure"));
        registry.counter("citibike.jdbc.query.errors",
                "operation", operation, "category", category).increment();
    }
}

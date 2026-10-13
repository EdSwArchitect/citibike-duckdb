package com.bscllc.learning.citibike.metrics;

import com.bscllc.learning.citibike.config.CitibikeConfig;
import com.bscllc.learning.citibike.health.DuckDbReadinessCheck;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.health.HealthCheckResponse;

/** Fixed resource gauges and a bounded readiness probe, independent of downloading. */
@Startup
@ApplicationScoped
public class MonitoringMetrics {
    private final CitibikeConfig config;
    private final DuckDbReadinessCheck readiness;

    public MonitoringMetrics(MeterRegistry registry, CitibikeConfig config, @org.eclipse.microprofile.health.Readiness DuckDbReadinessCheck readiness) {
        this.config = config;
        this.readiness = readiness;
        Gauge.builder("citibike.analytics.ready", this, m -> m.readiness.call().getStatus() == HealthCheckResponse.Status.UP ? 1 : 0).register(registry);
        Gauge.builder("citibike.jdbc.connections.limit", this, m -> m.config.jdbc().maxConnections()).register(registry);
        Gauge.builder("citibike.jdbc.query.timeout.seconds", this, m -> m.config.jdbc().queryTimeout().toMillis() / 1000.0).register(registry);
        Gauge.builder("citibike.jdbc.acquire.timeout.seconds", this, m -> m.config.jdbc().acquireTimeout().toMillis() / 1000.0).register(registry);
        Gauge.builder("citibike.duckdb.threads.limit", this, m -> m.config.duckdb().threads()).register(registry);
        Gauge.builder("citibike.duckdb.memory.limit.bytes", this, m -> bytes(m.config.duckdb().memoryLimit())).register(registry);
        Gauge.builder("citibike.duckdb.spill.limit.bytes", this, m -> bytes(m.config.duckdb().maxTempDirectorySize())).register(registry);
    }

    static long bytes(String size) {
        long value = Long.parseLong(size.substring(0, size.length() - 2));
        long unit = switch (size.substring(size.length() - 2)) {
            case "KB" -> 1000L; case "MB" -> 1000000L; case "GB" -> 1000000000L;
            default -> throw new IllegalArgumentException("Unsupported configured size unit");
        };
        return Math.multiplyExact(value, unit);
    }

    @Produces
    @Singleton
    public static MeterFilter latencyBuckets() {
        return new MeterFilter() {
            @Override public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig current) {
                if (!id.getName().equals("http.server.requests") && !id.getName().equals("citibike.jdbc.query.duration")) return current;
                return DistributionStatisticConfig.builder()
                        .serviceLevelObjectives(1e6, 5e6, 10e6, 25e6, 50e6, 100e6, 250e6, 500e6, 1e9, 2e9, 5e9, 10e9, 30e9, 60e9)
                        .build().merge(current);
            }
        };
    }
}

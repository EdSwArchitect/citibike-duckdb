package com.bscllc.learning.citibike.health;

import com.bscllc.learning.citibike.db.CitibikeDataLoader;
import com.bscllc.learning.citibike.db.DuckDbManager;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import java.sql.ResultSet;
import java.sql.Statement;

@Readiness
@ApplicationScoped
public class DuckDbReadinessCheck implements HealthCheck {
    private final DuckDbManager duckDb;
    private final CitibikeDataLoader loader;

    public DuckDbReadinessCheck(DuckDbManager duckDb, CitibikeDataLoader loader) {
        this.duckDb = duckDb;
        this.loader = loader;
    }

    @Override
    public HealthCheckResponse call() {
        if (!duckDb.isReady()) {
            return HealthCheckResponse.down("duckdb");
        }
        try {
            boolean reachable = duckDb.withConnection(connection -> {
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT 1")) {
                    return result.next() && result.getInt(1) == 1;
                }
            });
            if (!reachable) {
                return HealthCheckResponse.down("duckdb");
            }
            return HealthCheckResponse.named("duckdb")
                    .up()
                    .withData("filesLoaded", loader.report().filesLoaded())
                    .withData("rowsLoaded", loader.report().totalRows())
                    .build();
        } catch (RuntimeException e) {
            return HealthCheckResponse.down("duckdb");
        }
    }
}

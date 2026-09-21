package com.bscllc.learning.citibike.repository;

import com.bscllc.learning.citibike.db.DataAccessException;
import com.bscllc.learning.citibike.db.DatabaseBusyException;
import com.bscllc.learning.citibike.db.DuckDbManager;
import com.bscllc.learning.citibike.dto.ActivityBucketResponse;
import com.bscllc.learning.citibike.dto.CategorySummaryResponse;
import com.bscllc.learning.citibike.dto.PageResponse;
import com.bscllc.learning.citibike.dto.RideSummaryResponse;
import com.bscllc.learning.citibike.dto.RouteSummaryResponse;
import com.bscllc.learning.citibike.dto.StationActivityResponse;
import com.bscllc.learning.citibike.dto.StationImbalanceResponse;
import com.bscllc.learning.citibike.dto.TimeBucketResponse;
import com.bscllc.learning.citibike.metrics.CitibikeMetrics;
import com.bscllc.learning.citibike.validation.ActivityGroup;
import com.bscllc.learning.citibike.validation.RouteSort;
import com.bscllc.learning.citibike.validation.SortDirection;
import com.bscllc.learning.citibike.validation.StationDirection;
import com.bscllc.learning.citibike.validation.StationSort;
import com.bscllc.learning.citibike.validation.TimeInterval;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@ApplicationScoped
public class RideAnalyticsRepository {
    private static final Logger LOG = LoggerFactory.getLogger(RideAnalyticsRepository.class);

    private final DuckDbManager duckDb;
    private final CitibikeMetrics metrics;

    public RideAnalyticsRepository(DuckDbManager duckDb, CitibikeMetrics metrics) {
        this.duckDb = duckDb;
        this.metrics = metrics;
    }

    public RideSummaryResponse summary(RideFilter filter) {
        return timed("summary", () -> duckDb.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(RideSql.SUMMARY)) {
                configure(statement);
                bindFilter(statement, filter, 1);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return new RideSummaryResponse(filter.from(), filter.to(),
                            result.getLong("ride_count"),
                            nullableDouble(result, "average_duration_seconds"),
                            nullableDouble(result, "median_duration_seconds"),
                            nullableDouble(result, "p95_duration_seconds"),
                            result.getLong("rides_over_24_hours"));
                }
            }
        }));
    }

    public List<TimeBucketResponse> timeseries(RideFilter filter, TimeInterval interval) {
        return timed("timeseries", () -> duckDb.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(RideSql.timeseries(interval))) {
                configure(statement);
                bindFilter(statement, filter, 1);
                try (ResultSet result = statement.executeQuery()) {
                    List<TimeBucketResponse> rows = new ArrayList<>();
                    while (result.next()) {
                        rows.add(new TimeBucketResponse(
                                result.getTimestamp("bucket").toLocalDateTime(),
                                result.getLong("ride_count")));
                    }
                    return rows;
                }
            }
        }));
    }

    public List<CategorySummaryResponse> byBikeType(LocalDate from, LocalDate to) {
        return categorySummary("by-bike-type", RideSql.BY_BIKE_TYPE, from, to);
    }

    public List<CategorySummaryResponse> byRiderType(LocalDate from, LocalDate to) {
        return categorySummary("by-rider-type", RideSql.BY_RIDER_TYPE, from, to);
    }

    public List<ActivityBucketResponse> activity(RideFilter filter, ActivityGroup group) {
        return timed("activity", () -> duckDb.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(RideSql.activity(group))) {
                configure(statement);
                bindFilter(statement, filter, 1);
                try (ResultSet result = statement.executeQuery()) {
                    List<ActivityBucketResponse> rows = new ArrayList<>();
                    while (result.next()) {
                        rows.add(new ActivityBucketResponse(
                                result.getInt("bucket_order"), result.getString("label"),
                                result.getLong("ride_count"),
                                nullableDouble(result, "average_duration_seconds")));
                    }
                    return rows;
                }
            }
        }));
    }

    public PageResponse<StationActivityResponse> stations(
            RideFilter filter, StationDirection direction, StationSort sort,
            SortDirection order, int limit, int offset) {
        return timed("stations", () -> duckDb.withConnection(connection -> {
            String sql = RideSql.stations(direction, sort, order);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                configure(statement);
                int next = bindFilter(statement, filter, 1);
                statement.setInt(next++, limit);
                statement.setInt(next, offset);
                try (ResultSet result = statement.executeQuery()) {
                    List<StationActivityResponse> rows = new ArrayList<>();
                    long total = 0;
                    while (result.next()) {
                        total = result.getLong("total_count");
                        rows.add(new StationActivityResponse(
                                result.getString("station_id"),
                                result.getString("station_name"),
                                result.getLong("ride_count"),
                                nullableDouble(result, "average_duration_seconds")));
                    }
                    return new PageResponse<>(rows, limit, offset, total);
                }
            }
        }));
    }

    public PageResponse<RouteSummaryResponse> routes(
            RideFilter filter, boolean includeRoundTrips, RouteSort sort,
            SortDirection order, int limit, int offset) {
        return timed("routes", () -> duckDb.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(RideSql.routes(sort, order))) {
                configure(statement);
                int next = bindFilter(statement, filter, 1);
                statement.setBoolean(next++, includeRoundTrips);
                statement.setInt(next++, limit);
                statement.setInt(next, offset);
                try (ResultSet result = statement.executeQuery()) {
                    List<RouteSummaryResponse> rows = new ArrayList<>();
                    long total = 0;
                    while (result.next()) {
                        total = result.getLong("total_count");
                        rows.add(new RouteSummaryResponse(
                                result.getString("start_station_id"),
                                result.getString("start_station_name"),
                                result.getString("end_station_id"),
                                result.getString("end_station_name"),
                                result.getLong("ride_count"),
                                nullableDouble(result, "average_duration_seconds")));
                    }
                    return new PageResponse<>(rows, limit, offset, total);
                }
            }
        }));
    }

    public PageResponse<StationImbalanceResponse> imbalance(
            LocalDate from, LocalDate to, int limit, int offset) {
        return timed("station-imbalance", () -> duckDb.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(RideSql.IMBALANCE)) {
                configure(statement);
                statement.setTimestamp(1, Timestamp.valueOf(from.atStartOfDay()));
                statement.setTimestamp(2, Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
                statement.setTimestamp(3, Timestamp.valueOf(from.atStartOfDay()));
                statement.setTimestamp(4, Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
                statement.setInt(5, limit);
                statement.setInt(6, offset);
                try (ResultSet result = statement.executeQuery()) {
                    List<StationImbalanceResponse> rows = new ArrayList<>();
                    long total = 0;
                    while (result.next()) {
                        total = result.getLong("total_count");
                        rows.add(new StationImbalanceResponse(
                                result.getString("station_id"), result.getString("station_name"),
                                result.getLong("starts"), result.getLong("ends"),
                                result.getLong("net_starts")));
                    }
                    return new PageResponse<>(rows, limit, offset, total);
                }
            }
        }));
    }

    public DataQualityStats dataQuality() {
        return timed("data-quality", () -> duckDb.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(RideSql.DATA_QUALITY)) {
                configure(statement);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    Map<String, Long> missing = new LinkedHashMap<>();
                    missing.put("startStationName", result.getLong("missing_start_station_name"));
                    missing.put("startStationId", result.getLong("missing_start_station_id"));
                    missing.put("endStationName", result.getLong("missing_end_station_name"));
                    missing.put("endStationId", result.getLong("missing_end_station_id"));
                    missing.put("endLat", result.getLong("missing_end_lat"));
                    missing.put("endLng", result.getLong("missing_end_lng"));
                    return new DataQualityStats(missing,
                            result.getLong("duplicate_ride_ids"),
                            result.getLong("invalid_durations"),
                            result.getLong("rides_over_24_hours"));
                }
            }
        }));
    }

    private List<CategorySummaryResponse> categorySummary(
            String operation, String sql, LocalDate from, LocalDate to) {
        return timed(operation, () -> duckDb.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                configure(statement);
                statement.setTimestamp(1, Timestamp.valueOf(from.atStartOfDay()));
                statement.setTimestamp(2, Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
                try (ResultSet result = statement.executeQuery()) {
                    List<CategorySummaryResponse> rows = new ArrayList<>();
                    while (result.next()) {
                        rows.add(new CategorySummaryResponse(
                                result.getString("category"), result.getLong("ride_count"),
                                result.getDouble("percentage"),
                                nullableDouble(result, "average_duration_seconds")));
                    }
                    return rows;
                }
            }
        }));
    }

    private int bindFilter(PreparedStatement statement, RideFilter filter, int start)
            throws SQLException {
        int index = start;
        statement.setTimestamp(index++, Timestamp.valueOf(filter.fromInclusive()));
        statement.setTimestamp(index++, Timestamp.valueOf(filter.toExclusive()));
        index = bindNullableStringTwice(statement, index, filter.riderType());
        index = bindNullableStringTwice(statement, index, filter.bikeType());
        return index;
    }

    private static int bindNullableStringTwice(
            PreparedStatement statement, int start, String value) throws SQLException {
        for (int index = start; index < start + 2; index++) {
            if (value == null) {
                statement.setNull(index, Types.VARCHAR);
            } else {
                statement.setString(index, value);
            }
        }
        return start + 2;
    }

    private void configure(PreparedStatement statement) throws SQLException {
        statement.setQueryTimeout(duckDb.queryTimeoutSeconds());
    }

    private <T> T timed(String operation, Supplier<T> supplier) {
        Timer.Sample timer = metrics.startTimer();
        long started = System.nanoTime();
        try {
            T result = supplier.get();
            metrics.recordQuerySuccess(operation, timer);
            LOG.debug("DuckDB query {} completed in {} ms with {} result row(s)",
                    operation, elapsedMillis(started), resultRows(result));
            return result;
        } catch (RuntimeException e) {
            String category = errorCategory(e);
            metrics.recordQueryFailure(operation, category, timer);
            LOG.warn("DuckDB query {} failed after {} ms with category {}",
                    operation, elapsedMillis(started), category);
            throw e;
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static int resultRows(Object result) {
        if (result instanceof List<?> list) {
            return list.size();
        }
        if (result instanceof PageResponse<?> page) {
            return page.items().size();
        }
        return 1;
    }

    private static String errorCategory(RuntimeException exception) {
        if (exception instanceof DatabaseBusyException) {
            return "capacity";
        }
        if (exception instanceof DataAccessException) {
            return "sql";
        }
        return "runtime";
    }

    private static Double nullableDouble(ResultSet result, String column) throws SQLException {
        double value = result.getDouble(column);
        return result.wasNull() ? null : value;
    }
}

package com.bscllc.learning.citibike.repository;

import com.bscllc.learning.citibike.validation.ActivityGroup;
import com.bscllc.learning.citibike.validation.RouteSort;
import com.bscllc.learning.citibike.validation.SortDirection;
import com.bscllc.learning.citibike.validation.StationDirection;
import com.bscllc.learning.citibike.validation.StationSort;
import com.bscllc.learning.citibike.validation.TimeInterval;

final class RideSql {
    private static final String FILTER = """
            started_at >= ? AND started_at < ?
            AND (CAST(? AS VARCHAR) IS NULL OR member_casual = ?)
            AND (CAST(? AS VARCHAR) IS NULL OR rideable_type = ?)
            """;

    static final String SUMMARY = """
            SELECT count(*) AS ride_count,
                   avg(date_diff('second', started_at, ended_at)) AS average_duration_seconds,
                   quantile_cont(date_diff('second', started_at, ended_at), 0.5) AS median_duration_seconds,
                   quantile_cont(date_diff('second', started_at, ended_at), 0.95) AS p95_duration_seconds,
                   count(*) FILTER (WHERE date_diff('second', started_at, ended_at) > 86400)
                       AS rides_over_24_hours
            FROM rides
            WHERE %s
            """.formatted(FILTER);

    static final String BY_BIKE_TYPE = categorySummary("rideable_type");
    static final String BY_RIDER_TYPE = categorySummary("member_casual");

    static final String ROUTES_BASE = """
            WITH grouped AS (
                SELECT start_station_id, start_station_name,
                       end_station_id, end_station_name,
                       count(*) AS ride_count,
                       avg(date_diff('second', started_at, ended_at))
                           AS average_duration_seconds
                FROM rides
                WHERE %s
                  AND start_station_id IS NOT NULL
                  AND end_station_id IS NOT NULL
                  AND (? OR start_station_id <> end_station_id)
                GROUP BY start_station_id, start_station_name,
                         end_station_id, end_station_name
            )
            SELECT *, count(*) OVER () AS total_count
            FROM grouped
            """.formatted(FILTER);

    static final String IMBALANCE = """
            WITH starts AS (
                SELECT start_station_id AS station_id,
                       max(start_station_name) AS station_name,
                       count(*) AS starts
                FROM rides
                WHERE started_at >= ? AND started_at < ?
                  AND start_station_id IS NOT NULL
                GROUP BY start_station_id
            ), ends AS (
                SELECT end_station_id AS station_id,
                       max(end_station_name) AS station_name,
                       count(*) AS ends
                FROM rides
                WHERE started_at >= ? AND started_at < ?
                  AND end_station_id IS NOT NULL
                GROUP BY end_station_id
            ), grouped AS (
                SELECT coalesce(starts.station_id, ends.station_id) AS station_id,
                       coalesce(starts.station_name, ends.station_name) AS station_name,
                       coalesce(starts.starts, 0) AS starts,
                       coalesce(ends.ends, 0) AS ends,
                       coalesce(starts.starts, 0) - coalesce(ends.ends, 0) AS net_starts
                FROM starts FULL OUTER JOIN ends USING (station_id)
            )
            SELECT *, count(*) OVER () AS total_count
            FROM grouped
            ORDER BY abs(net_starts) DESC, station_name ASC
            LIMIT ? OFFSET ?
            """;

    static final String DATA_QUALITY = """
            SELECT
                count(*) - count(DISTINCT ride_id) AS duplicate_ride_ids,
                count(*) FILTER (WHERE ended_at <= started_at) AS invalid_durations,
                count(*) FILTER (
                    WHERE date_diff('second', started_at, ended_at) > 86400
                ) AS rides_over_24_hours,
                count(*) FILTER (WHERE start_station_name IS NULL) AS missing_start_station_name,
                count(*) FILTER (WHERE start_station_id IS NULL) AS missing_start_station_id,
                count(*) FILTER (WHERE end_station_name IS NULL) AS missing_end_station_name,
                count(*) FILTER (WHERE end_station_id IS NULL) AS missing_end_station_id,
                count(*) FILTER (WHERE end_lat IS NULL) AS missing_end_lat,
                count(*) FILTER (WHERE end_lng IS NULL) AS missing_end_lng
            FROM rides
            """;

    private RideSql() {
    }

    static String timeseries(TimeInterval interval) {
        return """
                SELECT date_trunc('%s', started_at) AS bucket, count(*) AS ride_count
                FROM rides
                WHERE %s
                GROUP BY bucket
                ORDER BY bucket
                """.formatted(interval.sqlValue(), FILTER);
    }

    static String activity(ActivityGroup group) {
        return switch (group) {
            case HOUR_OF_DAY -> """
                    SELECT CAST(hour(started_at) AS INTEGER) AS bucket_order,
                           strftime(started_at, '%%H:00') AS label,
                           count(*) AS ride_count,
                           avg(date_diff('second', started_at, ended_at))
                               AS average_duration_seconds
                    FROM rides
                    WHERE %s
                    GROUP BY bucket_order, label
                    ORDER BY bucket_order
                    """.formatted(FILTER);
            case DAY_OF_WEEK -> """
                    SELECT CAST(dayofweek(started_at) AS INTEGER) AS bucket_order,
                           dayname(started_at) AS label,
                           count(*) AS ride_count,
                           avg(date_diff('second', started_at, ended_at))
                               AS average_duration_seconds
                    FROM rides
                    WHERE %s
                    GROUP BY bucket_order, label
                    ORDER BY bucket_order
                    """.formatted(FILTER);
        };
    }

    static String stations(StationDirection direction, StationSort sort,
                           SortDirection order) {
        return """
                WITH grouped AS (
                    SELECT %s AS station_name, %s AS station_id,
                           count(*) AS ride_count,
                           avg(date_diff('second', started_at, ended_at))
                               AS average_duration_seconds
                    FROM rides
                    WHERE %s
                      AND %s IS NOT NULL
                      AND %s IS NOT NULL
                    GROUP BY %s, %s
                )
                SELECT *, count(*) OVER () AS total_count
                FROM grouped
                ORDER BY %s %s NULLS LAST, station_name ASC
                LIMIT ? OFFSET ?
                """.formatted(
                direction.nameColumn(), direction.idColumn(), FILTER,
                direction.nameColumn(), direction.idColumn(),
                direction.nameColumn(), direction.idColumn(),
                sort.sqlColumn(), order.sqlValue());
    }

    static String routes(RouteSort sort, SortDirection order) {
        return ROUTES_BASE + "\nORDER BY " + sort.sqlColumn() + " "
                + order.sqlValue() + " NULLS LAST, start_station_name, end_station_name"
                + "\nLIMIT ? OFFSET ?";
    }

    private static String categorySummary(String column) {
        return """
                WITH grouped AS (
                    SELECT %s AS category,
                           count(*) AS ride_count,
                           avg(date_diff('second', started_at, ended_at))
                               AS average_duration_seconds
                    FROM rides
                    WHERE started_at >= ? AND started_at < ?
                    GROUP BY %s
                )
                SELECT category, ride_count,
                       ride_count * 100.0 / sum(ride_count) OVER () AS percentage,
                       average_duration_seconds
                FROM grouped
                ORDER BY ride_count DESC, category
                """.formatted(column, column);
    }
}

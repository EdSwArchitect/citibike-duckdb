package com.bscllc.learning.citibike.service;

import com.bscllc.learning.citibike.config.CitibikeConfig;
import com.bscllc.learning.citibike.db.CitibikeDataLoader;
import com.bscllc.learning.citibike.db.LoadReport;
import com.bscllc.learning.citibike.dto.ActivityBucketResponse;
import com.bscllc.learning.citibike.dto.CategorySummaryResponse;
import com.bscllc.learning.citibike.dto.DataQualityResponse;
import com.bscllc.learning.citibike.dto.PageResponse;
import com.bscllc.learning.citibike.dto.RideSummaryResponse;
import com.bscllc.learning.citibike.dto.RouteSummaryResponse;
import com.bscllc.learning.citibike.dto.SourceFileLoadResponse;
import com.bscllc.learning.citibike.dto.StationActivityResponse;
import com.bscllc.learning.citibike.dto.StationImbalanceResponse;
import com.bscllc.learning.citibike.dto.TimeBucketResponse;
import com.bscllc.learning.citibike.repository.DataQualityStats;
import com.bscllc.learning.citibike.repository.RideAnalyticsRepository;
import com.bscllc.learning.citibike.repository.RideFilter;
import com.bscllc.learning.citibike.validation.ActivityGroup;
import com.bscllc.learning.citibike.validation.ApiEnum;
import com.bscllc.learning.citibike.validation.BikeType;
import com.bscllc.learning.citibike.validation.InvalidRequestException;
import com.bscllc.learning.citibike.validation.RiderType;
import com.bscllc.learning.citibike.validation.RouteSort;
import com.bscllc.learning.citibike.validation.SortDirection;
import com.bscllc.learning.citibike.validation.StationDirection;
import com.bscllc.learning.citibike.validation.StationSort;
import com.bscllc.learning.citibike.validation.TimeInterval;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

@ApplicationScoped
public class RideAnalyticsService {
    private final RideAnalyticsRepository repository;
    private final CitibikeDataLoader loader;
    private final CitibikeConfig config;

    public RideAnalyticsService(RideAnalyticsRepository repository,
                                CitibikeDataLoader loader,
                                CitibikeConfig config) {
        this.repository = repository;
        this.loader = loader;
        this.config = config;
    }

    public RideSummaryResponse summary(String from, String to, String riderType, String bikeType) {
        return repository.summary(filter(from, to, riderType, bikeType));
    }

    public List<TimeBucketResponse> timeseries(
            String from, String to, String riderType, String bikeType, String interval) {
        TimeInterval parsedInterval = ApiEnum.parse(
                TimeInterval.class, interval, TimeInterval.DAY, "interval");
        return repository.timeseries(filter(from, to, riderType, bikeType), parsedInterval);
    }

    public List<CategorySummaryResponse> byBikeType(String from, String to) {
        DateRange range = dateRange(from, to);
        return repository.byBikeType(range.from(), range.to());
    }

    public List<CategorySummaryResponse> byRiderType(String from, String to) {
        DateRange range = dateRange(from, to);
        return repository.byRiderType(range.from(), range.to());
    }

    public List<ActivityBucketResponse> activity(
            String from, String to, String riderType, String bikeType, String groupBy) {
        ActivityGroup group = ApiEnum.parse(
                ActivityGroup.class, groupBy, ActivityGroup.HOUR_OF_DAY, "groupBy");
        return repository.activity(filter(from, to, riderType, bikeType), group);
    }

    public PageResponse<StationActivityResponse> stations(
            String from, String to, String riderType, String bikeType,
            String direction, String sort, String order, Integer limit, Integer offset) {
        StationDirection parsedDirection = ApiEnum.parse(
                StationDirection.class, direction, StationDirection.START, "direction");
        StationSort parsedSort = ApiEnum.parse(
                StationSort.class, sort, StationSort.RIDE_COUNT, "sort");
        SortDirection parsedOrder = ApiEnum.parse(
                SortDirection.class, order, SortDirection.DESC, "order");
        Page page = page(limit, offset);
        return repository.stations(filter(from, to, riderType, bikeType),
                parsedDirection, parsedSort, parsedOrder, page.limit(), page.offset());
    }

    public PageResponse<RouteSummaryResponse> routes(
            String from, String to, String riderType, String bikeType,
            Boolean includeRoundTrips, String sort, String order,
            Integer limit, Integer offset) {
        RouteSort parsedSort = ApiEnum.parse(
                RouteSort.class, sort, RouteSort.RIDE_COUNT, "sort");
        SortDirection parsedOrder = ApiEnum.parse(
                SortDirection.class, order, SortDirection.DESC, "order");
        Page page = page(limit, offset);
        return repository.routes(filter(from, to, riderType, bikeType),
                Boolean.TRUE.equals(includeRoundTrips), parsedSort, parsedOrder,
                page.limit(), page.offset());
    }

    public PageResponse<StationImbalanceResponse> imbalance(
            String from, String to, Integer limit, Integer offset) {
        DateRange range = dateRange(from, to);
        Page page = page(limit, offset);
        return repository.imbalance(range.from(), range.to(), page.limit(), page.offset());
    }

    public DataQualityResponse dataQuality() {
        LoadReport report = loader.report();
        DataQualityStats stats = repository.dataQuality();
        List<SourceFileLoadResponse> sources = report.rowsBySource().entrySet().stream()
                .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                .map(entry -> new SourceFileLoadResponse(entry.getKey(), entry.getValue()))
                .toList();
        return new DataQualityResponse(
                report.filesDiscovered(), report.filesLoaded(), report.totalRows(), sources,
                report.minimumStartedAt(), report.maximumStartedAt(), stats.missingValues(),
                stats.duplicateRideIds(), stats.invalidDurations(), stats.ridesOver24Hours(),
                report.loadedAt());
    }

    private RideFilter filter(String from, String to, String riderType, String bikeType) {
        DateRange range = dateRange(from, to);
        RiderType parsedRider = ApiEnum.parse(RiderType.class, riderType, null, "riderType");
        BikeType parsedBike = ApiEnum.parse(BikeType.class, bikeType, null, "bikeType");
        return new RideFilter(range.from(), range.to(),
                parsedRider == null ? null : parsedRider.apiValue(),
                parsedBike == null ? null : parsedBike.apiValue());
    }

    private DateRange dateRange(String from, String to) {
        LoadReport report = loader.report();
        LocalDate defaultFrom = report.minimumStartedAt().toLocalDate();
        LocalDate defaultTo = report.maximumStartedAt().toLocalDate();
        LocalDate parsedFrom = parseDate(from, defaultFrom, "from");
        LocalDate parsedTo = parseDate(to, defaultTo, "to");
        if (parsedFrom.isAfter(parsedTo)) {
            throw new InvalidRequestException("from must not be after to");
        }
        long days = ChronoUnit.DAYS.between(parsedFrom, parsedTo) + 1;
        if (days > config.api().maxDateRangeDays()) {
            throw new InvalidRequestException("Date range exceeds the configured maximum");
        }
        return new DateRange(parsedFrom, parsedTo);
    }

    private static LocalDate parseDate(String value, LocalDate defaultValue, String name) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(name + " must use yyyy-MM-dd format");
        }
    }

    private Page page(Integer requestedLimit, Integer requestedOffset) {
        int limit = requestedLimit == null ? 20 : requestedLimit;
        int offset = requestedOffset == null ? 0 : requestedOffset;
        if (limit < 1 || limit > config.api().maxPageSize()) {
            throw new InvalidRequestException(
                    "limit must be between 1 and " + config.api().maxPageSize());
        }
        if (offset < 0 || offset > config.api().maxOffset()) {
            throw new InvalidRequestException(
                    "offset must be between 0 and " + config.api().maxOffset());
        }
        return new Page(limit, offset);
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }

    private record Page(int limit, int offset) {
    }
}

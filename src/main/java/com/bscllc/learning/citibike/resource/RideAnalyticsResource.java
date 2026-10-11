package com.bscllc.learning.citibike.resource;

import com.bscllc.learning.citibike.dto.ActivityBucketResponse;
import com.bscllc.learning.citibike.dto.CategorySummaryResponse;
import com.bscllc.learning.citibike.dto.DataQualityResponse;
import com.bscllc.learning.citibike.dto.PageResponse;
import com.bscllc.learning.citibike.dto.RideSummaryResponse;
import com.bscllc.learning.citibike.dto.RouteSummaryResponse;
import com.bscllc.learning.citibike.dto.StationActivityResponse;
import com.bscllc.learning.citibike.dto.StationImbalanceResponse;
import com.bscllc.learning.citibike.dto.TimeBucketResponse;
import com.bscllc.learning.citibike.service.RideAnalyticsService;
import io.smallrye.common.annotation.Blocking;
import com.bscllc.learning.citibike.validation.InvalidRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class RideAnalyticsResource {
    private final RideAnalyticsService service;

    public RideAnalyticsResource(RideAnalyticsService service) {
        this.service = service;
    }

    @GET
    @Path("/rides/summary")
    public RideSummaryResponse summary(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("riderType") String riderType,
            @QueryParam("bikeType") String bikeType) {
        return service.summary(from, to, riderType, bikeType);
    }

    @GET
    @Path("/rides/timeseries")
    public List<TimeBucketResponse> timeseries(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("riderType") String riderType,
            @QueryParam("bikeType") String bikeType,
            @QueryParam("interval") String interval) {
        return service.timeseries(from, to, riderType, bikeType, interval);
    }

    @GET
    @Path("/rides/by-bike-type")
    public List<CategorySummaryResponse> byBikeType(
            @QueryParam("from") String from,
            @QueryParam("to") String to) {
        return service.byBikeType(from, to);
    }

    @GET
    @Path("/rides/by-rider-type")
    public List<CategorySummaryResponse> byRiderType(
            @QueryParam("from") String from,
            @QueryParam("to") String to) {
        return service.byRiderType(from, to);
    }

    @GET
    @Path("/rides/activity")
    public List<ActivityBucketResponse> activity(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("riderType") String riderType,
            @QueryParam("bikeType") String bikeType,
            @QueryParam("groupBy") String groupBy) {
        return service.activity(from, to, riderType, bikeType, groupBy);
    }

    @GET
    @Path("/stations")
    public PageResponse<StationActivityResponse> stations(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("riderType") String riderType,
            @QueryParam("bikeType") String bikeType,
            @QueryParam("direction") String direction,
            @QueryParam("sort") String sort,
            @QueryParam("order") String order,
            @QueryParam("limit") String limit,
            @QueryParam("offset") String offset,
            @QueryParam("stationName") String stationName) {
        return service.stations(from, to, riderType, bikeType, direction, sort, order,
                integer(limit, "limit"), integer(offset, "offset"), stationName);
    }

    @GET
    @Path("/routes")
    public PageResponse<RouteSummaryResponse> routes(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("riderType") String riderType,
            @QueryParam("bikeType") String bikeType,
            @QueryParam("includeRoundTrips") String includeRoundTrips,
            @QueryParam("sort") String sort,
            @QueryParam("order") String order,
            @QueryParam("limit") String limit,
            @QueryParam("offset") String offset) {
        return service.routes(from, to, riderType, bikeType, bool(includeRoundTrips),
                sort, order, integer(limit, "limit"), integer(offset, "offset"));
    }

    @GET
    @Path("/stations/imbalance")
    public PageResponse<StationImbalanceResponse> imbalance(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("limit") String limit,
            @QueryParam("offset") String offset) {
        return service.imbalance(from, to, integer(limit, "limit"), integer(offset, "offset"));
    }

    private static Integer integer(String value, String name) {
        if (value == null) return null;
        if (!value.matches("-?[0-9]{1,10}")) throw new InvalidRequestException(name + " must be an integer");
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new InvalidRequestException(name + " must be an integer");
        }
    }

    private static Boolean bool(String value) {
        if (value == null || "false".equalsIgnoreCase(value)) return false;
        if ("true".equalsIgnoreCase(value)) return true;
        throw new InvalidRequestException("includeRoundTrips must be true or false");
    }

    @GET
    @Path("/data-quality")
    public DataQualityResponse dataQuality() {
        return service.dataQuality();
    }
}

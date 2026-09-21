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
import jakarta.ws.rs.DefaultValue;
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
            @QueryParam("limit") Integer limit,
            @QueryParam("offset") Integer offset) {
        return service.stations(from, to, riderType, bikeType, direction, sort, order,
                limit, offset);
    }

    @GET
    @Path("/routes")
    public PageResponse<RouteSummaryResponse> routes(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("riderType") String riderType,
            @QueryParam("bikeType") String bikeType,
            @QueryParam("includeRoundTrips") @DefaultValue("false") Boolean includeRoundTrips,
            @QueryParam("sort") String sort,
            @QueryParam("order") String order,
            @QueryParam("limit") Integer limit,
            @QueryParam("offset") Integer offset) {
        return service.routes(from, to, riderType, bikeType, includeRoundTrips,
                sort, order, limit, offset);
    }

    @GET
    @Path("/stations/imbalance")
    public PageResponse<StationImbalanceResponse> imbalance(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("limit") Integer limit,
            @QueryParam("offset") Integer offset) {
        return service.imbalance(from, to, limit, offset);
    }

    @GET
    @Path("/data-quality")
    public DataQualityResponse dataQuality() {
        return service.dataQuality();
    }
}

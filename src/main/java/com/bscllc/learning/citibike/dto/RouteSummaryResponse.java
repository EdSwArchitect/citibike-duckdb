package com.bscllc.learning.citibike.dto;

public record RouteSummaryResponse(
        String startStationId,
        String startStationName,
        String endStationId,
        String endStationName,
        long rideCount,
        Double averageDurationSeconds
) {
}

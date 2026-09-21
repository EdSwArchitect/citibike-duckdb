package com.bscllc.learning.citibike.dto;

public record StationActivityResponse(
        String stationId,
        String stationName,
        long rideCount,
        Double averageDurationSeconds
) {
}

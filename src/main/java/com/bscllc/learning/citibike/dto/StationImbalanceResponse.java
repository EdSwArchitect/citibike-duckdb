package com.bscllc.learning.citibike.dto;

public record StationImbalanceResponse(
        String stationId,
        String stationName,
        long starts,
        long ends,
        long netStarts
) {
}

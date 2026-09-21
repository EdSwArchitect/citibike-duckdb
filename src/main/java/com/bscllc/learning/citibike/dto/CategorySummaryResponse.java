package com.bscllc.learning.citibike.dto;

public record CategorySummaryResponse(
        String category,
        long rideCount,
        double percentage,
        Double averageDurationSeconds
) {
}

package com.bscllc.learning.citibike.dto;

import java.time.LocalDate;

public record RideSummaryResponse(
        LocalDate from,
        LocalDate to,
        long rideCount,
        Double averageDurationSeconds,
        Double medianDurationSeconds,
        Double p90DurationSeconds,
        Double p95DurationSeconds,
        long roundTripCount,
        long ridesOver24Hours
) {
}

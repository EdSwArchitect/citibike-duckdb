package com.bscllc.learning.citibike.repository;

import java.util.Map;

public record DataQualityStats(
        Map<String, Long> missingValues,
        long duplicateRideIds,
        long invalidDurations,
        long ridesOver24Hours
) {
}

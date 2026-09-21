package com.bscllc.learning.citibike.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record DataQualityResponse(
        int filesDiscovered,
        int filesLoaded,
        long totalRows,
        List<SourceFileLoadResponse> sources,
        LocalDateTime minimumStartedAt,
        LocalDateTime maximumStartedAt,
        Map<String, Long> missingValues,
        long duplicateRideIds,
        long invalidDurations,
        long ridesOver24Hours,
        Instant loadedAt
) {
    public DataQualityResponse {
        sources = List.copyOf(sources);
        missingValues = Map.copyOf(missingValues);
    }
}

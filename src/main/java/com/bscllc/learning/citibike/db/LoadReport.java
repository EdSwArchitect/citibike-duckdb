package com.bscllc.learning.citibike.db;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

public record LoadReport(
        int filesDiscovered,
        int filesLoaded,
        long totalRows,
        Map<String, Long> rowsBySource,
        LocalDateTime minimumStartedAt,
        LocalDateTime maximumStartedAt,
        Instant loadedAt
) {
    public LoadReport {
        rowsBySource = Map.copyOf(rowsBySource);
    }
}

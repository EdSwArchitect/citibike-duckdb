package com.bscllc.learning.citibike.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record RideFilter(
        LocalDate from,
        LocalDate to,
        String riderType,
        String bikeType
) {
    public LocalDateTime fromInclusive() {
        return from.atStartOfDay();
    }

    public LocalDateTime toExclusive() {
        return to.plusDays(1).atStartOfDay();
    }
}

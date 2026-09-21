package com.bscllc.learning.citibike.dto;

import java.time.Instant;

public record ApiErrorResponse(Instant timestamp, String code, String message) {
}

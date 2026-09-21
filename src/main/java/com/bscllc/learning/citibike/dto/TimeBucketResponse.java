package com.bscllc.learning.citibike.dto;

import java.time.LocalDateTime;

public record TimeBucketResponse(LocalDateTime bucket, long rideCount) {
}

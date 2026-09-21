package com.bscllc.learning.citibike.dto;

public record ActivityBucketResponse(
        int bucketOrder,
        String label,
        long rideCount,
        Double averageDurationSeconds
) {
}

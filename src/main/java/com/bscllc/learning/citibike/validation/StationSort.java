package com.bscllc.learning.citibike.validation;

public enum StationSort implements ApiEnum {
    RIDE_COUNT("rideCount", "ride_count"),
    AVERAGE_DURATION("averageDuration", "average_duration_seconds");

    private final String apiValue;
    private final String sqlColumn;

    StationSort(String apiValue, String sqlColumn) {
        this.apiValue = apiValue;
        this.sqlColumn = sqlColumn;
    }

    @Override
    public String apiValue() {
        return apiValue;
    }

    public String sqlColumn() {
        return sqlColumn;
    }
}

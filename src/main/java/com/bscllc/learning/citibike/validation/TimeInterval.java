package com.bscllc.learning.citibike.validation;

public enum TimeInterval implements ApiEnum {
    HOUR("hour", "hour"),
    DAY("day", "day");

    private final String apiValue;
    private final String sqlValue;

    TimeInterval(String apiValue, String sqlValue) {
        this.apiValue = apiValue;
        this.sqlValue = sqlValue;
    }

    @Override
    public String apiValue() {
        return apiValue;
    }

    public String sqlValue() {
        return sqlValue;
    }
}

package com.bscllc.learning.citibike.validation;

public enum ActivityGroup implements ApiEnum {
    HOUR_OF_DAY("hourOfDay"),
    DAY_OF_WEEK("dayOfWeek");

    private final String apiValue;

    ActivityGroup(String apiValue) {
        this.apiValue = apiValue;
    }

    @Override
    public String apiValue() {
        return apiValue;
    }
}

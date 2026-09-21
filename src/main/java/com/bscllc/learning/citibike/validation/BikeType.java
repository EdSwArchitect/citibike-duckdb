package com.bscllc.learning.citibike.validation;

public enum BikeType implements ApiEnum {
    CLASSIC("classic_bike"),
    ELECTRIC("electric_bike"),
    DOCKED("docked_bike");

    private final String apiValue;

    BikeType(String apiValue) {
        this.apiValue = apiValue;
    }

    @Override
    public String apiValue() {
        return apiValue;
    }
}

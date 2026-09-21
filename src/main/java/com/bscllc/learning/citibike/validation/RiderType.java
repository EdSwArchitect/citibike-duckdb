package com.bscllc.learning.citibike.validation;

public enum RiderType implements ApiEnum {
    MEMBER("member"),
    CASUAL("casual");

    private final String apiValue;

    RiderType(String apiValue) {
        this.apiValue = apiValue;
    }

    @Override
    public String apiValue() {
        return apiValue;
    }
}

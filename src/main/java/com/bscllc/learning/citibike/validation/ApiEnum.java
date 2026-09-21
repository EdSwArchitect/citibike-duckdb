package com.bscllc.learning.citibike.validation;

import java.util.Arrays;

public interface ApiEnum {
    String apiValue();

    static <T extends Enum<T> & ApiEnum> T parse(
            Class<T> type, String value, T defaultValue, String parameterName) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Arrays.stream(type.getEnumConstants())
                .filter(candidate -> candidate.apiValue().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException(
                        "Invalid " + parameterName + " value"));
    }
}

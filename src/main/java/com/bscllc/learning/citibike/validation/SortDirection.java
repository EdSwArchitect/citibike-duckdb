package com.bscllc.learning.citibike.validation;

public enum SortDirection implements ApiEnum {
    ASC("asc", "ASC"),
    DESC("desc", "DESC");

    private final String apiValue;
    private final String sqlValue;

    SortDirection(String apiValue, String sqlValue) {
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

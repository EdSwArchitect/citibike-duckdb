package com.bscllc.learning.citibike.validation;

public enum StationDirection implements ApiEnum {
    START("start", "start_station_name", "start_station_id"),
    END("end", "end_station_name", "end_station_id");

    private final String apiValue;
    private final String nameColumn;
    private final String idColumn;

    StationDirection(String apiValue, String nameColumn, String idColumn) {
        this.apiValue = apiValue;
        this.nameColumn = nameColumn;
        this.idColumn = idColumn;
    }

    @Override
    public String apiValue() {
        return apiValue;
    }

    public String nameColumn() {
        return nameColumn;
    }

    public String idColumn() {
        return idColumn;
    }
}

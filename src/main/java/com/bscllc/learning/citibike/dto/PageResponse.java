package com.bscllc.learning.citibike.dto;

import java.util.List;

public record PageResponse<T>(List<T> items, int limit, int offset, long total) {
    public PageResponse {
        items = List.copyOf(items);
    }
}

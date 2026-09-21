package com.bscllc.learning.citibike;

import com.bscllc.learning.citibike.validation.ApiEnum;
import com.bscllc.learning.citibike.validation.InvalidRequestException;
import com.bscllc.learning.citibike.validation.SortDirection;
import com.bscllc.learning.citibike.validation.StationSort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValidationTest {
    @Test
    void enumParsingUsesOnlyAllowlistedValues() {
        assertEquals(StationSort.RIDE_COUNT,
                ApiEnum.parse(StationSort.class, "rideCount", null, "sort"));
        assertEquals(SortDirection.DESC,
                ApiEnum.parse(SortDirection.class, null, SortDirection.DESC, "order"));
        assertThrows(InvalidRequestException.class,
                () -> ApiEnum.parse(StationSort.class, "ride_count;drop", null, "sort"));
    }
}

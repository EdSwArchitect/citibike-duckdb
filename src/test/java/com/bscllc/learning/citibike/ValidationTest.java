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
    @Test
    void everyEnumRejectsUnknownOptionsAndRoundTripsItsApiValue() {
        check(com.bscllc.learning.citibike.validation.ActivityGroup.class);
        check(com.bscllc.learning.citibike.validation.BikeType.class);
        check(com.bscllc.learning.citibike.validation.RiderType.class);
        check(com.bscllc.learning.citibike.validation.RouteSort.class);
        check(com.bscllc.learning.citibike.validation.SortDirection.class);
        check(com.bscllc.learning.citibike.validation.StationDirection.class);
        check(com.bscllc.learning.citibike.validation.StationSort.class);
        check(com.bscllc.learning.citibike.validation.TimeInterval.class);
    }

    private <T extends Enum<T> & ApiEnum> void check(Class<T> type) {
        for (T option : type.getEnumConstants()) assertEquals(option, ApiEnum.parse(type, option.apiValue(), null, "option"));
        assertThrows(InvalidRequestException.class, () -> ApiEnum.parse(type, "LEAK_PROBE; DROP TABLE rides", null, "option"));
    }

}

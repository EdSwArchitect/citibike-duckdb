package com.bscllc.learning.citibike.db;

public class DatabaseBusyException extends RuntimeException {
    public DatabaseBusyException(String message) {
        super(message);
    }
}

package com.bscllc.learning;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppTest {
    private static final String CSV_PATH = "src/main/resources/citibike.csv";

    @Test
    void duckDbCanReadCitibikeCsv() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM read_csv('" + CSV_PATH + "')")) {

            assertTrue(rs.next());
            assertEquals(1500, rs.getInt(1));
        }
    }
}

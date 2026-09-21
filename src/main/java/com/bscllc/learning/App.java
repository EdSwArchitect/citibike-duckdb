package com.bscllc.learning;

import java.sql.*;

public class App {
    private static final String CSV_PATH = "src/main/resources/citibike.csv";

    public static void main(String[] args) {
        // "jdbc:duckdb:" creates an in-memory database.
        // Use "jdbc:duckdb:my_db.db" for a persistent database file.
        String url = "jdbc:duckdb:";

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            System.out.println("Connected to DuckDB successfully.");

            // OPTION 1: Create a new table directly from the CSV (Auto-infers schema)
            String createTableSql = String.format(
                    "CREATE TABLE citibike AS SELECT * FROM read_csv('%s')", CSV_PATH
            );
            stmt.execute(createTableSql);
            System.out.println("Table created and CSV data loaded successfully.");
            System.out.println();

            String querySql = "SELECT ride_id, rideable_type FROM citibike LIMIT 10";

            try (ResultSet rs = stmt.executeQuery(querySql)) {
                System.out.println("Sample rows:");
                while (rs.next()) {
                    System.out.println("ride_id: " + rs.getString(1) + ", rideable_type: " + rs.getString(2));
                }
            }

            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM citibike")) {
                if (rs.next()) {
                    System.out.println();
                    System.out.println("Total rows: " + rs.getInt(1));
                }
            }

            /*
            // OPTION 2: Load CSV data into an existing table using COPY
            // (Best for production ETL with pre-defined schemas)
            stmt.execute("CREATE TABLE existing_users (id INTEGER, name VARCHAR, age INTEGER)");
            String copySql = String.format(
                    "COPY existing_users FROM '%s' (HEADER TRUE, DELIMITER ',')", CSV_PATH
            );
            stmt.execute(copySql);
            System.out.println("CSV data appended to existing table.");

            // OPTION 3: Directly query the CSV file without importing it into a table
            String querySql = String.format("SELECT name, age FROM '%s' WHERE age > 21", CSV_PATH);

            try (ResultSet rs = stmt.executeQuery(querySql)) {
                while (rs.next()) {
                    System.out.println("User: " + rs.getString("name") + ", Age: " + rs.getInt("age"));
                }
            }

             */

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}

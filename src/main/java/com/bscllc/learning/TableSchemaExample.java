package com.bscllc.learning;

import java.sql.*;

public class TableSchemaExample {
    // "jdbc:duckdb:" creates an in-memory database.
    // Use "jdbc:duckdb:my_db.db" for a persistent database file.

    public static void main(String[] args) {
        String url = "jdbc:duckdb:";
        String csvPath = "src/main/resources/citibike.csv";

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement())
        {
            String createTableSql = String.format(
                    "CREATE TABLE citibike AS SELECT * FROM read_csv('%s')", csvPath);

            stmt.execute(createTableSql);
            System.out.println("Table created and CSV data loaded successfully.");

            String querySql = String.format("SELECT * FROM citibike");

            try (ResultSet rs = stmt.executeQuery(querySql)) {
                while (rs.next()) {
                    System.out.println("1: " + rs.getString(1) + ", 2: " + rs.getString(2) + ", 3: " +
                            rs.getString(3) + ", 5: " + rs.getString(5)  +
                            ", 6: " + rs.getString(6) + ", 7: " + rs.getString(7) +
                            ", 8: " + rs.getString(8) + ", 9: " + rs.getString(9) + ", 10: " + rs.getString(10)
                    );
                }
            }

            System.out.println();

            // 1. Get Database MetaData
            DatabaseMetaData metaData = conn.getMetaData();

            // 2. Fetch column information
            // Parameters: catalog, schemaPattern, tableNamePattern, columnNamePattern
            // Pass null for parameters you want to ignore, or specify them to narrow down results
            try (ResultSet columns = metaData.getColumns(null, null, "citibike", null)) {

                System.out.printf("%-20s %-15s %-10s%n", "Column Name", "Data Type", "Size");
                System.out.println("--------------------------------------------------");

                while (columns.next()) {
                    String columnName = columns.getString("COLUMN_NAME");
                    String typeName = columns.getString("TYPE_NAME");
                    int columnSize = columns.getInt("COLUMN_SIZE");
                    boolean isNullable = "YES".equals(columns.getString("IS_NULLABLE"));

                    System.out.printf("%-20s %-15s %-10d %s%n",
                            columnName, typeName, columnSize, isNullable ? "" : "NOT NULL");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

package com.bscllc.learning.citibike.db;
import com.bscllc.learning.citibike.db.CsvResourceCatalog.CsvResource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
public final class CsvImporter {
    private CsvImporter() {}
    static final List<String> EXPECTED_HEADER = List.of(
            "ride_id", "rideable_type", "started_at", "ended_at",
            "start_station_name", "start_station_id", "end_station_name",
            "end_station_id", "start_lat", "start_lng", "end_lat", "end_lng",
            "member_casual");

    static final String CREATE_STAGING_SQL = """
            CREATE TABLE rides_staging (
                ride_id VARCHAR NOT NULL,
                rideable_type VARCHAR NOT NULL,
                started_at TIMESTAMP NOT NULL,
                ended_at TIMESTAMP NOT NULL,
                start_station_name VARCHAR,
                start_station_id VARCHAR,
                end_station_name VARCHAR,
                end_station_id VARCHAR,
                start_lat DOUBLE NOT NULL,
                start_lng DOUBLE NOT NULL,
                end_lat DOUBLE,
                end_lng DOUBLE,
                member_casual VARCHAR NOT NULL,
                source_file VARCHAR NOT NULL
            )
            """;

    static final String LOAD_CSV_SQL = """
            INSERT INTO rides_staging
            SELECT ride_id, rideable_type, started_at, ended_at,
                   start_station_name, start_station_id, end_station_name, end_station_id,
                   start_lat, start_lng, end_lat, end_lng, member_casual, ?
            FROM read_csv(?, header = true, strict_mode = true, columns = {
                'ride_id': 'VARCHAR',
                'rideable_type': 'VARCHAR',
                'started_at': 'TIMESTAMP',
                'ended_at': 'TIMESTAMP',
                'start_station_name': 'VARCHAR',
                'start_station_id': 'VARCHAR',
                'end_station_name': 'VARCHAR',
                'end_station_id': 'VARCHAR',
                'start_lat': 'DOUBLE',
                'start_lng': 'DOUBLE',
                'end_lat': 'DOUBLE',
                'end_lng': 'DOUBLE',
                'member_casual': 'VARCHAR'
            })
            """;

    public static void createStage(Connection c, boolean temporary) throws SQLException {
        try(Statement s=c.createStatement()) { s.execute("DROP TABLE IF EXISTS rides_staging");
            s.execute(temporary?CREATE_STAGING_SQL.replace("CREATE TABLE", "CREATE TEMP TABLE").replace("start_lat DOUBLE NOT NULL", "start_lat DOUBLE").replace("start_lng DOUBLE NOT NULL", "start_lng DOUBLE"):CREATE_STAGING_SQL); }
    }
    public static void loadFile(Connection c, Path path, String provenance, int timeout) throws SQLException, IOException {
        validateHeader(new CsvResource(provenance,path.toUri().toURL()));
        try(PreparedStatement s=c.prepareStatement(LOAD_CSV_SQL)) {
            s.setQueryTimeout(timeout);s.setString(1,provenance);s.setString(2,path.toAbsolutePath().toString());s.executeUpdate();
        }
    }
    public static void validateHeader(CsvResource resource) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8))) {
            StringBuilder firstLine=new StringBuilder();int ch;
            while((ch=reader.read())!=-1 && ch!='\n') {
                if(firstLine.length()>=4096)throw new IllegalStateException("CSV header exceeds the supported schema limit");
                firstLine.append((char)ch);
            }
            if(!firstLine.isEmpty() && firstLine.charAt(firstLine.length()-1)=='\r')firstLine.setLength(firstLine.length()-1);
            String header=firstLine.isEmpty() && ch==-1?null:firstLine.toString();
            if (header == null) {
                throw new IllegalStateException("CSV resource is empty: " + resource.name());
            }
            if (header.startsWith("\uFEFF")) {
                header = header.substring(1);
            }
            List<String> columns = Arrays.stream(header.split(",", -1))
                    .map(String::trim)
                    .map(CsvImporter::stripQuotes)
                    .toList();
            if (!columns.equals(EXPECTED_HEADER)) {
                throw new IllegalStateException("Unexpected CSV header in " + resource.name());
            }
        }
    }

    public static void validateLoadedData(Connection connection, boolean duplicates) throws SQLException {
        validateLoadedData(connection,duplicates,false);
    }
    public static void validateLoadedData(Connection connection, boolean duplicates, boolean pairedStartNulls) throws SQLException {
        validateLoadedData(connection,duplicates,pairedStartNulls,1800);
    }
    public static void validateLoadedData(Connection connection, boolean duplicates, boolean pairedStartNulls, int timeout) throws SQLException {
        if(pairedStartNulls)assertNoRows(connection,"SELECT 1 FROM rides_staging WHERE (start_lat IS NULL)<>(start_lng IS NULL) LIMIT 1","CSV contains unpaired start coordinates",timeout);
        else assertNoRows(connection,"SELECT 1 FROM rides_staging WHERE start_lat IS NULL OR start_lng IS NULL LIMIT 1","CSV is missing required start coordinates",timeout);
        assertNoRows(connection, """
                SELECT 1 FROM rides_staging
                WHERE trim(ride_id) = '' OR NOT isfinite(started_at) OR NOT isfinite(ended_at)
                LIMIT 1
                """, "CSV contains an invalid required identifier or timestamp", timeout);
        assertNoRows(connection, """
                SELECT 1 FROM rides_staging
                WHERE rideable_type NOT IN ('classic_bike', 'electric_bike', 'docked_bike')
                   OR member_casual NOT IN ('member', 'casual')
                LIMIT 1
                """, "CSV contains an unsupported categorical value", timeout);
        assertNoRows(connection, """
                SELECT 1 FROM rides_staging
                WHERE ended_at <= started_at
                LIMIT 1
                """, "CSV contains a non-positive ride duration", timeout);
        assertNoRows(connection, """
                SELECT 1 FROM rides_staging
                WHERE start_lat NOT BETWEEN -90 AND 90
                   OR start_lng NOT BETWEEN -180 AND 180
                   OR (end_lat IS NULL) <> (end_lng IS NULL)
                   OR (end_lat IS NOT NULL AND end_lat NOT BETWEEN -90 AND 90)
                   OR (end_lng IS NOT NULL AND end_lng NOT BETWEEN -180 AND 180)
                LIMIT 1
                """, "CSV contains invalid coordinates", timeout);
        if (duplicates) {
            assertNoRows(connection, """
                    SELECT ride_id FROM rides_staging
                    GROUP BY ride_id HAVING count(*) > 1
                    LIMIT 1
                    """, "Duplicate ride_id found across CSV resources", timeout);
        }
    }

    private static void assertNoRows(Connection connection, String sql, String message, int timeout)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (ResultSet result = statement.executeQuery(sql)) {
            if (result.next()) {
                throw new IllegalStateException(message);
            }
            }
        }
    }

    private static long countRows(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) FROM rides_staging")) {
            result.next();
            return result.getLong(1);
        }
    }

    private static LocalDateTime[] readBounds(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT min(started_at), max(started_at) FROM rides_staging")) {
            result.next();
            if (result.getTimestamp(1) == null || result.getTimestamp(2) == null) {
                throw new IllegalStateException("No ride rows were loaded from CSV resources");
            }
            return new LocalDateTime[]{
                    result.getTimestamp(1).toLocalDateTime(),
                    result.getTimestamp(2).toLocalDateTime()
            };
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

}

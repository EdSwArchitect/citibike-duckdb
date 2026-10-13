package com.bscllc.learning.citibike.ingestion;

import com.bscllc.learning.citibike.db.CsvImporter;
import com.bscllc.learning.citibike.db.CsvResourceCatalog.CsvResource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Opt-in full-file audit, never run by ordinary CI. Outputs aggregate counts only. */
public class ArchiveProfileMain {
    public static void main(String[] args)throws Exception {
        var cfg=new SmallRyeConfigBuilder().withMapping(IngestionConfig.class)
            .withConverter(Duration.class,100,new io.quarkus.runtime.configuration.DurationConverter()).build().getConfigMapping(IngestionConfig.class);
        Path work=Files.createTempDirectory("citibike-full-audit-");long started=System.nanoTime();
        try {
            var extracted=new SafeArchiveExtractor(cfg).extract(Path.of(args[0]),false,YearMonth.parse(args[1]),work,System.nanoTime()+Duration.ofHours(2).toNanos());
            Properties p=new Properties();p.setProperty("memory_limit","512MB");p.setProperty("threads","4");p.setProperty("max_temp_directory_size","8GB");p.setProperty("temp_directory",work.resolve("spill").toString());
            try(Connection c=DriverManager.getConnection("jdbc:duckdb:",p);Statement s=c.createStatement()) {
                String[] columns={"ride_id","rideable_type","started_at","ended_at","start_station_name","start_station_id","end_station_name","end_station_id","start_lat","start_lng","end_lat","end_lng","member_casual"};
                s.execute("CREATE TABLE raw_profile("+String.join(",",Arrays.stream(columns).map(n->n+" VARCHAR").toList())+")");
                for(var file:extracted.csvs()) {
                    CsvImporter.validateHeader(new CsvResource(file.entry(),file.path().toUri().toURL()));
                    try(var q=c.prepareStatement("INSERT INTO raw_profile SELECT * FROM read_csv(?,header=true,all_varchar=true,strict_mode=true)")){q.setString(1,file.path().toString());q.executeUpdate();}
                }
                String sql="""
                    SELECT count(*) AS row_count,
                      count(*) FILTER(WHERE ride_id IS NULL OR trim(ride_id)='') missing_ids,
                      count(*) FILTER(WHERE try_cast(started_at AS TIMESTAMP) IS NULL OR try_cast(ended_at AS TIMESTAMP) IS NULL) invalid_timestamps,
                      count(*) FILTER(WHERE try_cast(ended_at AS TIMESTAMP)=try_cast(started_at AS TIMESTAMP)) zero_duration,
                      count(*) FILTER(WHERE try_cast(ended_at AS TIMESTAMP)<try_cast(started_at AS TIMESTAMP)) negative_duration,
                      count(*) FILTER(WHERE rideable_type NOT IN ('classic_bike','electric_bike','docked_bike') OR rideable_type IS NULL OR member_casual NOT IN ('member','casual') OR member_casual IS NULL) invalid_categories,
                      count(*) FILTER(WHERE try_cast(start_lat AS DOUBLE) IS NULL OR try_cast(start_lng AS DOUBLE) IS NULL) missing_start_coordinates,
                      count(*) FILTER(WHERE (end_lat IS NULL)<>(end_lng IS NULL)) unpaired_end_coordinates,
                      count(*) FILTER(WHERE try_cast(start_lat AS DOUBLE) NOT BETWEEN -90 AND 90 OR try_cast(start_lng AS DOUBLE) NOT BETWEEN -180 AND 180 OR try_cast(end_lat AS DOUBLE) NOT BETWEEN -90 AND 90 OR try_cast(end_lng AS DOUBLE) NOT BETWEEN -180 AND 180) invalid_coordinates,
                      count(*) FILTER(WHERE epoch(try_cast(ended_at AS TIMESTAMP)-try_cast(started_at AS TIMESTAMP))>86400) outliers,
                      min(try_cast(started_at AS TIMESTAMP)) minimum_started,max(try_cast(started_at AS TIMESTAMP)) maximum_started
                    FROM raw_profile
                    """;
                try(ResultSet r=s.executeQuery(sql)){r.next();for(int i=1;i<=r.getMetaData().getColumnCount();i++)System.out.println(r.getMetaData().getColumnLabel(i)+"="+r.getString(i));}
                try(ResultSet r=s.executeQuery("SELECT count(*)-count(DISTINCT ride_id) FROM raw_profile")){r.next();System.out.println("duplicate_ids="+r.getLong(1));}
                try(var q=c.prepareStatement("""
                    SELECT count(*) FILTER(WHERE (start_lat IS NULL)<>(start_lng IS NULL)) unpaired_start_coordinates,
                    count(*) FILTER(WHERE start_lat IS NULL AND start_lng IS NULL) paired_missing_start_coordinates,
                    count(*) FILTER(WHERE try_cast(started_at AS TIMESTAMP)<?) prior_month_starts,
                    count(*) FILTER(WHERE try_cast(ended_at AS TIMESTAMP)>=?) next_month_ends,
                    count(*) FILTER(WHERE try_cast(ended_at AS TIMESTAMP)<? OR try_cast(started_at AS TIMESTAMP)>=?) outside_requested_month,
                    min(try_cast(ended_at AS TIMESTAMP)) minimum_ended,max(try_cast(ended_at AS TIMESTAMP)) maximum_ended
                    FROM raw_profile
                    """)) {
                    YearMonth requested=YearMonth.parse(args[1]);q.setTimestamp(1,Timestamp.valueOf(requested.atDay(1).atStartOfDay()));
                    q.setTimestamp(2,Timestamp.valueOf(requested.plusMonths(1).atDay(1).atStartOfDay()));q.setTimestamp(3,Timestamp.valueOf(requested.atDay(1).atStartOfDay()));
                    q.setTimestamp(4,Timestamp.valueOf(requested.plusMonths(1).atDay(1).atStartOfDay()));
                    try(var r=q.executeQuery()){r.next();for(int i=1;i<=r.getMetaData().getColumnCount();i++)System.out.println(r.getMetaData().getColumnLabel(i)+"="+r.getString(i));}
                }
                System.out.println("csv_files="+extracted.csvs().size());System.out.println("expanded_bytes="+extracted.expandedBytes());
                System.out.println("audit_seconds="+((System.nanoTime()-started)/1_000_000_000.0));
            }
        }catch(Exception failure){throw new IllegalStateException("Full archive audit failed; inspect schema, ZIP, and resource limits");}
        finally{MonthlyImporter.deleteDirectory(work);}
    }
}

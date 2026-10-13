package com.bscllc.learning.citibike.ingestion;

import com.bscllc.learning.citibike.db.*;
import com.bscllc.learning.citibike.db.CsvResourceCatalog.CsvResource;
import com.bscllc.learning.citibike.download.DownloadStateStore;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.io.*;
import java.util.zip.*;

@ApplicationScoped
public class MonthlyImporter {
    public static final String POLICY="monthly-v2";
    private final DuckDbManager db;
    private final IngestionConfig cfg;
    private final Clock clock;
    public MonthlyImporter(DuckDbManager db,IngestionConfig cfg,Clock clock){this.db=db;this.cfg=cfg;this.clock=clock;}
    public boolean importMonth(IngestionRepository.Job job,IngestionRepository.Version version,
                               SafeArchiveExtractor.Extracted extracted,long deadline) {
        return db.withWriter(c -> {
            int timeout=seconds(deadline);
            YearMonth month=YearMonth.parse(job.month());
            try {
                try(var s=c.prepareStatement("SELECT materialization_sha256,policy,rows FROM active_partitions WHERE city=? AND month=?")) {
                    s.setString(1,job.city());s.setString(2,job.month());try(var r=s.executeQuery()) {
                        if(r.next() && r.getString(1).equals(extracted.materializationSha256()) && r.getString(2).equals(POLICY)) {
                            c.setAutoCommit(false);try {
                                IngestionRepository.finish(c,job,"UNCHANGED",null,version.id(),r.getLong(3),null,clock.instant());c.commit();return false;
                            }catch(SQLException|RuntimeException e){c.rollback();throw e;}finally{c.setAutoCommit(true);}
                        }
                    }
                }
                CsvImporter.createStage(c,true);
                for(var csv:extracted.csvs()) {
                    check(deadline);
                    try { CsvImporter.loadFile(c,csv.path(),provenance(job,csv.entry()),seconds(deadline)); }
                    catch(IllegalStateException e){throw new IngestionFailure("unsupported_schema");}
                    catch(IOException|SQLException e){throw new IngestionFailure("invalid_data");}
                }
                try { CsvImporter.validateLoadedData(c,true,true,seconds(deadline)); }
                catch(IllegalStateException e) {throw new IngestionFailure(e.getMessage().startsWith("Duplicate")?"duplicate_rides":"invalid_data");}
                try(var s=c.prepareStatement("SELECT 1 FROM rides_staging WHERE ended_at<? OR started_at>=? LIMIT 1")) {
                    s.setQueryTimeout(seconds(deadline));s.setTimestamp(1,Timestamp.valueOf(month.atDay(1).atStartOfDay()));
                    s.setTimestamp(2,Timestamp.valueOf(month.plusMonths(1).atDay(1).atStartOfDay()));
                    try(var r=s.executeQuery()){if(r.next())throw new IngestionFailure("wrong_month");}
                }
                c.setAutoCommit(false);
                try {
                    try(var s=c.prepareStatement("SELECT 1 FROM rides_staging s JOIN rides r USING(ride_id) WHERE NOT(r.source_city=? AND r.source_month=?) LIMIT 1")) {
                        s.setQueryTimeout(seconds(deadline));s.setString(1,job.city());s.setString(2,job.month());try(var r=s.executeQuery()){if(r.next())throw new IngestionFailure("duplicate_rides");}
                    }
                    for(String table:List.of("rides","source_csv","active_partitions")) {
                        String city=table.equals("rides")?"source_city":"city",date=table.equals("rides")?"source_month":"month";
                        try(var s=c.prepareStatement("DELETE FROM "+table+" WHERE "+city+"=? AND "+date+"=?")){
                            s.setQueryTimeout(seconds(deadline));s.setString(1,job.city());s.setString(2,job.month());s.executeUpdate();
                        }
                    }
                    try(var s=c.prepareStatement("INSERT INTO rides SELECT *,?,?,? FROM rides_staging")) {
                        s.setQueryTimeout(seconds(deadline));s.setString(1,job.city());s.setString(2,job.month());s.setString(3,version.id());s.executeUpdate();
                    }
                    long rows;LocalDateTime min,max;
                    try(var s=c.createStatement()) {s.setQueryTimeout(seconds(deadline));try(var r=s.executeQuery("SELECT count(*),min(started_at),max(started_at) FROM rides_staging")){
                        r.next();rows=r.getLong(1);if(rows==0)throw new IngestionFailure("invalid_data");min=r.getTimestamp(2).toLocalDateTime();max=r.getTimestamp(3).toLocalDateTime();
                    }}
                    for(var csv:extracted.csvs())source(c,job,version.id(),csv,seconds(deadline),db.rejectEmptyCsvFiles());
                    try(var s=c.prepareStatement("INSERT INTO active_partitions VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                        s.setString(1,job.city());s.setString(2,job.month());s.setString(3,version.id());s.setString(4,job.id());
                        s.setString(5,extracted.materializationSha256());s.setString(6,extracted.nestedEntry());s.setInt(7,extracted.csvs().size());s.setLong(8,rows);
                        s.setTimestamp(9,Timestamp.valueOf(min));s.setTimestamp(10,Timestamp.valueOf(max));s.setString(11,POLICY);s.setTimestamp(12,Timestamp.from(clock.instant()));s.executeUpdate();
                    }
                    IngestionRepository.finish(c,job,"COMMITTED",null,version.id(),rows,null,clock.instant());
                    check(deadline);beforeCommit(c);c.commit();afterCommit();db.activate();db.recordCommittedData(LoadReports.read(c));return true;
                }catch(SQLException|RuntimeException e){c.rollback();throw e;}
                finally{c.setAutoCommit(true);}
            }finally {
                try(var s=c.createStatement()){s.execute("DROP TABLE IF EXISTS rides_staging");}
            }
        });
    }
    protected void beforeCommit(Connection connection) throws SQLException {}
    protected void afterCommit() {}
    private int seconds(long deadline){check(deadline);return (int)Math.max(1,Math.min(cfg.sqlTimeout().toSeconds(),(deadline-System.nanoTime())/1_000_000_000));}
    private static void check(long deadline){if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline)throw new IngestionFailure("deadline");}
    static String provenance(IngestionRepository.Job job,String entry){return job.city()+"/"+job.month()+"/"+entry;}
    private static void source(Connection c,IngestionRepository.Job job,String version,SafeArchiveExtractor.Csv csv,int timeout,boolean rejectEmpty)throws SQLException {
        try(var s=c.prepareStatement("""
            INSERT INTO source_csv
            SELECT ?,?,?,?,?,?,count(*),count(*) FILTER(WHERE start_station_id IS NULL OR start_station_name IS NULL),
              count(*) FILTER(WHERE end_station_id IS NULL OR end_station_name IS NULL),
              count(*) FILTER(WHERE end_lat IS NULL OR end_lng IS NULL),
              count(*) FILTER(WHERE date_diff('second',started_at,ended_at)>86400),
              count(*) FILTER(WHERE start_lat IS NULL AND start_lng IS NULL)
            FROM rides_staging WHERE source_file=?
            """)) {
            s.setQueryTimeout(timeout);s.setString(1,version);s.setString(2,job.city());s.setString(3,job.month());s.setString(4,csv.entry());
            s.setString(5,provenance(job,csv.entry()));s.setString(6,csv.sha256());s.setString(7,provenance(job,csv.entry()));s.executeUpdate();
        }
        try(var s=c.prepareStatement("SELECT rows FROM source_csv WHERE city=? AND month=? AND entry=?")) {
            s.setString(1,job.city());s.setString(2,job.month());s.setString(3,csv.entry());try(var r=s.executeQuery()){r.next();if(rejectEmpty && r.getLong(1)==0)throw new IngestionFailure("invalid_data");}
        }
    }
    /** Explicit one-month NYC bundled bootstrap; subsequently replaced by the full monthly partition. */
    public static void bootstrap(DuckDbManager db,List<CsvResource> resources) {
        db.withWriter(c -> {
            Path work=null;
            try {
                work=Files.createTempDirectory(db.dataDirectory(),"bootstrap-");CsvImporter.createStage(c,true);
                List<SafeArchiveExtractor.Csv> files=new ArrayList<>();
                for(var resource:resources) {
                    Path p=Files.createTempFile(work,"csv-",".csv");try(var in=resource.openStream()){Files.copy(in,p,StandardCopyOption.REPLACE_EXISTING);}
                    CsvImporter.loadFile(c,p,resource.name(),1800);files.add(new SafeArchiveExtractor.Csv(resource.name(),p,DownloadStateStore.digest(p)));
                }
                CsvImporter.validateLoadedData(c,true,true);YearMonth month;
                try(var s=c.createStatement();var r=s.executeQuery("SELECT min(started_at),max(started_at) FROM rides_staging")) {
                    r.next();if(r.getTimestamp(1)==null)throw new IllegalStateException("Empty bundled bootstrap");
                    month=YearMonth.from(r.getTimestamp(1).toLocalDateTime());
                    if(!month.equals(YearMonth.from(r.getTimestamp(2).toLocalDateTime())))throw new IllegalStateException("Bundled bootstrap must contain one calendar month");
                }
                Path archives=db.dataDirectory().resolve("archives");Files.createDirectories(archives);String id=UUID.randomUUID().toString();
                Path archive=archives.resolve("archive-"+id+".zip");Path partial=Files.createTempFile(archives,"bootstrap-",".part");
                try(var zip=new ZipOutputStream(Files.newOutputStream(partial))) {for(var f:files){zip.putNextEntry(new ZipEntry(f.entry()));Files.copy(f.path(),zip);zip.closeEntry();}}
                try(var file=java.nio.channels.FileChannel.open(partial,StandardOpenOption.WRITE)){file.force(true);}
                Files.move(partial,archive,StandardCopyOption.ATOMIC_MOVE);
                try(var directory=java.nio.channels.FileChannel.open(archives,StandardOpenOption.READ)){directory.force(true);}
                String sha=DownloadStateStore.digest(archive);Instant now=Instant.now();
                c.setAutoCommit(false);
                try {
                    try(var s=c.prepareStatement("INSERT INTO archive_versions VALUES(?, 'bundled', NULL,NULL,NULL,?,?,?,?)")){
                        s.setString(1,id);s.setLong(2,Files.size(archive));s.setString(3,sha);s.setString(4,archive.getFileName().toString());s.setTimestamp(5,Timestamp.from(now));s.executeUpdate();
                    }
                    try(var s=c.prepareStatement("INSERT INTO ingestion_jobs(id,city,month,trigger,status,attempts,created_at,version_id,rows) SELECT ?,'NYC',?,'bootstrap','COMMITTED',1,?,?,count(*) FROM rides_staging")){
                        s.setString(1,id);s.setString(2,month.toString());s.setTimestamp(3,Timestamp.from(now));s.setString(4,id);s.executeUpdate();
                    }
                    for(var f:files)try(var s=c.prepareStatement("UPDATE rides_staging SET source_file=? WHERE source_file=?")){
                        s.setString(1,"NYC/"+month+"/"+f.entry());s.setString(2,f.entry());s.executeUpdate();
                    }
                    try(var s=c.prepareStatement("INSERT INTO rides SELECT *,'NYC',?,? FROM rides_staging")){s.setString(1,month.toString());s.setString(2,id);s.executeUpdate();}
                    try(var s=c.prepareStatement("INSERT INTO active_partitions SELECT 'NYC',?,?,?,?,NULL,?,count(*),min(started_at),max(started_at),?,? FROM rides_staging")) {
                        s.setString(1,month.toString());s.setString(2,id);s.setString(3,id);s.setString(4,sha);s.setInt(5,files.size());s.setString(6,POLICY);s.setTimestamp(7,Timestamp.from(now));s.executeUpdate();
                    }
                    var job=new IngestionRepository.Job(id,"NYC",month.toString(),"bootstrap","COMMITTED",1,null,now,now,now,null,id,0);
                    for(var f:files)source(c,job,id,f,1800,db.rejectEmptyCsvFiles());c.commit();
                }catch(SQLException|RuntimeException e){c.rollback();throw e;}finally{c.setAutoCommit(true);}
                return null;
            }catch(IOException e){throw new IllegalStateException("Bundled bootstrap could not be copied");}
            finally{if(work!=null)deleteDirectory(work);try(var s=c.createStatement()){s.execute("DROP TABLE IF EXISTS rides_staging");}}
        });
    }
    public static void deleteDirectory(Path directory) {
        try(var files=Files.walk(directory)){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}
        catch(IOException ignored){}
    }
}

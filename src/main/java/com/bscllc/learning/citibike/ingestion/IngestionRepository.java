package com.bscllc.learning.citibike.ingestion;

import com.bscllc.learning.citibike.db.*;
import com.bscllc.learning.citibike.download.LatestArchiveDownloader.Source;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.*;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class IngestionRepository {
    public record Job(String id,String city,String month,String trigger,String status,int attempts,String reason,
                      Instant createdAt,Instant startedAt,Instant finishedAt,Instant nextRetry,String versionId,long rows) {
        @com.fasterxml.jackson.annotation.JsonProperty public String diagnostic(){return reason==null?null:IngestionFailure.diagnostic(reason);}
    }
    public record Version(String id,String objectKey,String etag,String upstreamVersion,String modified,long bytes,String sha256,String archive,Instant downloadedAt) {}
    public record Coverage(String city,String month,long rows,int files,LocalDateTime minimumStartedAt,LocalDateTime maximumStartedAt,Instant committedAt,long missingStartCoordinates) {}
    public record Status(List<Coverage> coverage,long pending,List<Job> recentFailures) {}
    public static final Set<String> STATUSES=Set.of("QUEUED","RUNNING","WAITING","COMMITTED","UNCHANGED","FAILED","OVERDUE");
    private final DuckDbManager db;
    private final IngestionConfig cfg;
    private final Clock clock;
    public IngestionRepository(DuckDbManager db,IngestionConfig cfg,Clock clock){this.db=db;this.cfg=cfg;this.clock=clock;}
    public List<Job> enqueue(ArchiveCatalog.City city,List<YearMonth> months,String trigger,boolean forceRetry) {
        if(!Set.of("monthly","catch-up","manual","backfill","bootstrap").contains(trigger))throw new IllegalArgumentException("Unsupported ingestion trigger");
        return db.withWriter(c -> {
            c.setAutoCommit(false);
            try {
                long pending=scalar(c,"SELECT count(*) FROM ingestion_jobs WHERE status IN ('QUEUED','RUNNING','WAITING')");
                List<Job> result=new ArrayList<>();
                for(YearMonth month:months) {
                    Job existing=null;
                    try(var s=c.prepareStatement("SELECT * FROM ingestion_jobs WHERE city=? AND month=? AND status IN ('QUEUED','RUNNING','WAITING') ORDER BY created_at DESC LIMIT 1")) {
                        s.setString(1,city.name());s.setString(2,month.toString());try(var r=s.executeQuery()){if(r.next())existing=job(r);}
                    }
                    if(existing!=null) {
                        if(forceRetry && existing.status().equals("WAITING")) {
                            try(var s=c.prepareStatement("UPDATE ingestion_jobs SET status='QUEUED',next_retry=NULL,trigger=? WHERE id=?")){s.setString(1,trigger);s.setString(2,existing.id());s.executeUpdate();}
                            existing=readJob(c,existing.id());
                        }
                        result.add(existing);continue;
                    }
                    if(++pending>cfg.maxQueuedJobs())throw new DatabaseBusyException("Ingestion queue is full");
                    String id=UUID.randomUUID().toString();
                    try(var s=c.prepareStatement("INSERT INTO ingestion_jobs(id,city,month,trigger,status,created_at) VALUES(?,?,?,?,'QUEUED',?)")) {
                        s.setString(1,id);s.setString(2,city.name());s.setString(3,month.toString());s.setString(4,trigger);s.setTimestamp(5,Timestamp.from(clock.instant()));s.executeUpdate();
                    }
                    result.add(readJob(c,id));
                }
                c.commit();return List.copyOf(result);
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}finally{c.setAutoCommit(true);}
        });
    }
    public boolean hasEligible(){return db.withMetadata(c->{try(var s=c.prepareStatement("SELECT 1 FROM ingestion_jobs WHERE status='QUEUED' OR (status='WAITING' AND next_retry<=?) LIMIT 1")){
        s.setTimestamp(1,Timestamp.from(clock.instant()));try(var r=s.executeQuery()){return r.next();}
    }});}
    public Job takeNext() {
        return db.withWriter(c -> {
            c.setAutoCommit(false);
            try {
                Job selected=null;
                try(var s=c.prepareStatement("SELECT * FROM ingestion_jobs WHERE status='QUEUED' OR (status='WAITING' AND next_retry<=?) ORDER BY month,city,created_at LIMIT 1")) {
                    s.setTimestamp(1,Timestamp.from(clock.instant()));try(var r=s.executeQuery()){if(r.next())selected=job(r);}
                }
                if(selected!=null) {
                    try(var s=c.prepareStatement("UPDATE ingestion_jobs SET status='RUNNING',attempts=attempts+1,started_at=?,finished_at=NULL,next_retry=NULL,reason=NULL WHERE id=?")) {
                        s.setTimestamp(1,Timestamp.from(clock.instant()));s.setString(2,selected.id());s.executeUpdate();
                    }
                    selected=readJob(c,selected.id());
                    try(var s=c.prepareStatement("INSERT INTO ingestion_attempts(job_id,attempt,started_at,status,trigger) VALUES(?,?,?,'RUNNING',?)")){
                        s.setString(1,selected.id());s.setInt(2,selected.attempts());s.setTimestamp(3,Timestamp.from(clock.instant()));s.setString(4,selected.trigger());s.executeUpdate();
                    }
                }
                c.commit();return selected;
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}finally{c.setAutoCommit(true);}
        });
    }
    public void finish(Job job,String status,String reason,String version,long rows,Instant retry) {
        db.withWriter(c -> {c.setAutoCommit(false);try {finish(c,job,status,reason,version,rows,retry,clock.instant());c.commit();return null;}
            catch(SQLException|RuntimeException e){c.rollback();throw e;}finally{c.setAutoCommit(true);}});
    }
    public static void finish(Connection c,Job job,String status,String reason,String version,long rows,Instant retry,Instant finished) throws SQLException {
        try(var s=c.prepareStatement("UPDATE ingestion_jobs SET status=?,reason=?,version_id=?,rows=?,next_retry=?,finished_at=? WHERE id=?")) {
            s.setString(1,status);s.setString(2,reason);s.setString(3,version);s.setLong(4,rows);
            s.setTimestamp(5,retry==null?null:Timestamp.from(retry));s.setTimestamp(6,Timestamp.from(finished));s.setString(7,job.id());s.executeUpdate();
        }
        try(var s=c.prepareStatement("UPDATE ingestion_attempts SET status=?,reason=?,finished_at=?,rows=? WHERE job_id=? AND attempt=?")) {
            s.setString(1,status);s.setString(2,reason);s.setTimestamp(3,Timestamp.from(finished));s.setLong(4,rows);s.setString(5,job.id());s.setInt(6,job.attempts());s.executeUpdate();
        }
    }
    public void recover() {
        db.withWriter(c -> {c.setAutoCommit(false);try(var s=c.createStatement()) {
            s.execute("UPDATE ingestion_attempts SET status='INTERRUPTED',reason='interrupted',finished_at=current_timestamp WHERE status='RUNNING'");
            s.execute("UPDATE ingestion_jobs SET status='QUEUED',reason='interrupted',next_retry=NULL WHERE status='RUNNING'");c.commit();return null;
        }catch(SQLException|RuntimeException e){c.rollback();throw e;}finally{c.setAutoCommit(true);}});
    }
    public Version cached(Source source) {
        if(source.etag()==null && source.version()==null)return null;
        return db.withMetadata(c -> {
            try(var s=c.prepareStatement("SELECT * FROM archive_versions WHERE object_key=? AND etag IS NOT DISTINCT FROM ? AND upstream_version IS NOT DISTINCT FROM ? AND bytes=? ORDER BY downloaded_at DESC LIMIT 1")) {
                s.setString(1,source.key());s.setString(2,source.etag());s.setString(3,source.version());s.setLong(4,source.size());
                try(var r=s.executeQuery()){return r.next()?version(r):null;}
            }
        });
    }
    public Version saveVersion(Source source,String checksum,String archive) {
        return db.withWriter(c -> {
            String id=UUID.randomUUID().toString();Instant now=clock.instant();
            try(var s=c.prepareStatement("INSERT INTO archive_versions VALUES(?,?,?,?,?,?,?,?,?)")) {
                s.setString(1,id);s.setString(2,source.key());s.setString(3,source.etag());s.setString(4,source.version());s.setString(5,source.modified());
                s.setLong(6,source.size());s.setString(7,checksum);s.setString(8,archive);s.setTimestamp(9,Timestamp.from(now));s.executeUpdate();
            }
            return new Version(id,source.key(),source.etag(),source.version(),source.modified(),source.size(),checksum,archive,now);
        });
    }
    public long cachedBytes(){return db.withMetadata(c->scalar(c,"SELECT coalesce(sum(bytes),0) FROM archive_versions"));}
    public Job get(String id){return db.withMetadata(c->readJob(c,id));}
    public List<Job> jobs(String status,int limit,int offset) {
        if(status!=null && !STATUSES.contains(status))throw new com.bscllc.learning.citibike.validation.InvalidRequestException("Unsupported job status");
        return db.withMetadata(c->{try(var s=c.prepareStatement("SELECT * FROM ingestion_jobs WHERE (? IS NULL OR status=?) ORDER BY created_at DESC,id LIMIT ? OFFSET ?")){
            s.setString(1,status);s.setString(2,status);s.setInt(3,limit);s.setInt(4,offset);try(var r=s.executeQuery()){List<Job> result=new ArrayList<>();while(r.next())result.add(job(r));return result;}
        }});
    }
    public Status status() {
        return db.withMetadata(c->{c.setAutoCommit(false);try {
            List<Coverage> coverage=new ArrayList<>();
            try(var s=c.createStatement();var r=s.executeQuery("SELECT p.*,coalesce((SELECT sum(missing_start_coordinates) FROM source_csv s WHERE s.city=p.city AND s.month=p.month),0) missing_coordinates FROM active_partitions p ORDER BY month,city")){
                while(r.next())coverage.add(new Coverage(r.getString("city"),r.getString("month"),r.getLong("rows"),r.getInt("csv_count"),r.getTimestamp("minimum_started").toLocalDateTime(),r.getTimestamp("maximum_started").toLocalDateTime(),r.getTimestamp("committed_at").toInstant(),r.getLong("missing_coordinates")));
            }
            List<Job> failed=new ArrayList<>();try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM ingestion_jobs WHERE status IN ('FAILED','OVERDUE') ORDER BY created_at DESC LIMIT 20")){while(r.next())failed.add(job(r));}
            long pending=scalar(c,"SELECT count(*) FROM ingestion_jobs WHERE status IN ('QUEUED','RUNNING','WAITING')");c.commit();return new Status(coverage,pending,failed);
        }catch(SQLException|RuntimeException e){c.rollback();throw e;}finally{c.setAutoCommit(true);}});
    }
    public record Active(String version,String materialization,String policy,long rows) {}
    public Active active(Job job) {return db.withMetadata(c->{try(var s=c.prepareStatement("SELECT version_id,materialization_sha256,policy,rows FROM active_partitions WHERE city=? AND month=?")){
        s.setString(1,job.city());s.setString(2,job.month());try(var r=s.executeQuery()){return r.next()?new Active(r.getString(1),r.getString(2),r.getString(3),r.getLong(4)):null;}
    }});}
    public Job latest(ArchiveCatalog.City city,YearMonth month){return db.withMetadata(c->{try(var s=c.prepareStatement("SELECT * FROM ingestion_jobs WHERE city=? AND month=? ORDER BY created_at DESC LIMIT 1")){
        s.setString(1,city.name());s.setString(2,month.toString());try(var r=s.executeQuery()){return r.next()?job(r):null;}
    }});}
    public boolean committed(Job job) {return db.withMetadata(c->{try(var s=c.prepareStatement("SELECT 1 FROM active_partitions WHERE job_id=?")){s.setString(1,job.id());try(var r=s.executeQuery()){return r.next();}}});}
    static Job readJob(Connection c,String id)throws SQLException {
        try(var s=c.prepareStatement("SELECT * FROM ingestion_jobs WHERE id=?")){s.setString(1,id);try(var r=s.executeQuery()){return r.next()?job(r):null;}}
    }
    static long scalar(Connection c,String sql)throws SQLException {try(var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
    private static Instant instant(ResultSet r,String name)throws SQLException {Timestamp t=r.getTimestamp(name);return t==null?null:t.toInstant();}
    private static Job job(ResultSet r)throws SQLException {
        return new Job(r.getString("id"),r.getString("city"),r.getString("month"),r.getString("trigger"),r.getString("status"),r.getInt("attempts"),r.getString("reason"),instant(r,"created_at"),instant(r,"started_at"),instant(r,"finished_at"),instant(r,"next_retry"),r.getString("version_id"),r.getLong("rows"));
    }
    private static Version version(ResultSet r)throws SQLException {return new Version(r.getString("id"),r.getString("object_key"),r.getString("etag"),r.getString("upstream_version"),r.getString("modified"),r.getLong("bytes"),r.getString("sha256"),r.getString("archive"),instant(r,"downloaded_at"));}
}

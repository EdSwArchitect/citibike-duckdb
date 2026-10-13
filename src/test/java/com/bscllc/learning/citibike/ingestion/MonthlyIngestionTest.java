package com.bscllc.learning.citibike.ingestion;

import com.bscllc.learning.citibike.db.*;
import com.bscllc.learning.citibike.config.CitibikeConfig;
import com.bscllc.learning.citibike.metrics.CitibikeMetrics;
import com.bscllc.learning.citibike.download.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bscllc.learning.citibike.ingestion.IngestionFixtures.*;

class MonthlyIngestionTest {
    @TempDir Path root;IngestionFixtures fixture;DuckDbManager db;IngestionRepository repo;IngestionConfig cfg;
    SimpleMeterRegistry meters;MonthlyImporter importer;IngestionCoordinator coordinator;
    Clock clock=Clock.fixed(Instant.parse("2023-08-02T12:00:00Z"),ZoneOffset.UTC);
    @BeforeEach void setup()throws Exception {
        fixture=new IngestionFixtures();configure(Map.of());fixture.put("202307-citibike-tripdata.zip",Map.of("part1.csv",HEADER+ROW));
    }
    void configure(Map<String,String> overrides)throws Exception {
        var settings=IngestionFixtures.settings(root,fixture.uri(),overrides);cfg=settings.getConfigMapping(IngestionConfig.class);
        meters=new SimpleMeterRegistry();db=new DuckDbManager(settings.getConfigMapping(CitibikeConfig.class),new CitibikeMetrics(meters));
        db.start();db.activate(false);Files.createDirectories(root.resolve("jobs"));Files.createDirectories(root.resolve("archives"));
        repo=new IngestionRepository(db,cfg,clock);importer=new MonthlyImporter(db,cfg,clock);
        coordinator=new IngestionCoordinator(db,cfg,repo,importer,new IngestionMetrics(meters,cfg,db),clock);
    }
    @AfterEach void cleanup(){coordinator.stop(null);db.shutdown();meters.close();fixture.close();}
    IngestionRepository.Job queue(String month){return repo.enqueue(ArchiveCatalog.City.NYC,List.of(YearMonth.parse(month)),"manual",true).getFirst();}
    IngestionRepository.Job run(String month){var j=queue(month);var running=repo.takeNext();assertEquals(j.id(),running.id());coordinator.run(running);return repo.get(j.id());}
    long rows(){return db.withConnection(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM rides")){r.next();return r.getLong(1);}});}
    @Test void multiCsvQuotedSchemaProvenanceAndRestart()throws Exception {
        fixture.put("202307-citibike-tripdata.zip",Map.of("folder/b.csv",HEADER+ROW.replace("R1,","R2,"),"folder/a.csv",HEADER+ROW,"__MACOSX/._a.csv","sidecar"));
        assertEquals("COMMITTED",run("2023-07").status());assertEquals(2,rows());assertTrue(db.isReady());assertEquals(2,meters.get("citibike.csv.rows").gauge().value());
        var report=db.withSnapshot(LoadReports::read);assertEquals(2,report.filesLoaded());assertEquals(2,report.totalRows());assertTrue(report.rowsBySource().containsKey("NYC/2023-07/folder/a.csv"));
        db.shutdown();var old=meters;configure(Map.of());old.close();assertEquals(2,rowsAfterActivation());
    }
    long rowsAfterActivation(){db.activate();return rows();}
    @Test void unchangedAndRevisedArchivesReplaceWholeMonth()throws Exception {
        assertEquals("COMMITTED",run("2023-07").status());assertEquals("UNCHANGED",run("2023-07").status());assertEquals(1,fixture.gets.get());
        fixture.put("202307-citibike-tripdata.zip",Map.of("corrected.csv",HEADER+ROW.replace("R1,","NEW,")));
        assertEquals("COMMITTED",run("2023-07").status());assertEquals(1,rows());
        assertEquals("NEW",db.withConnection(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT ride_id FROM rides")){r.next();return r.getString(1);}}));
        assertEquals(1,db.withSnapshot(LoadReports::read).filesLoaded());
    }
    @Test void invalidRevisionAndDuplicateMonthsPreserveCommittedData()throws Exception {
        run("2023-07");fixture.put("202307-citibike-tripdata.zip",Map.of("bad.csv","legacy,header\ninvalid,row\n"));
        var failed=run("2023-07");assertEquals("FAILED",failed.status());assertEquals("unsupported_schema",failed.reason());assertEquals(1,rows());assertTrue(db.isReady());
        fixture.put("202306-citibike-tripdata.zip",Map.of("june.csv",HEADER+ROW.replace("2023-07-01","2023-06-01")));
        assertEquals("duplicate_rides",run("2023-06").reason());assertEquals(1,rows());
    }
    @Test void duplicatesWithinArchiveInvalidValuesAndWrongMonthRejectAll()throws Exception {
        fixture.put("202307-citibike-tripdata.zip",Map.of("a.csv",HEADER+ROW,"b.csv",HEADER+ROW));assertEquals("duplicate_rides",run("2023-07").reason());assertFalse(db.isReady());
        fixture.put("202307-citibike-tripdata.zip",Map.of("a.csv",HEADER+ROW.replace("classic_bike","unknown")));assertEquals("invalid_data",run("2023-07").reason());
        fixture.put("202307-citibike-tripdata.zip",Map.of("a.csv",HEADER+ROW.replace("2023-07-01","2023-06-01")));assertEquals("wrong_month",run("2023-07").reason());
    }
    @Test void annualNestedArchiveAndCacheReuse()throws Exception {
        fixture.objects.clear();byte[] july=zipStrings(Map.of("trips.csv",HEADER+ROW)),june=zipStrings(Map.of("trips.csv",HEADER+ROW.replace("R1,","JUNE,").replace("2023-07-01","2023-06-01")));
        fixture.objects.put("2023-citibike-tripdata.zip",zip(Map.of("2023/202307-citibike-tripdata.zip",july,"2023/202306-citibike-tripdata.zip",june,"__MACOSX/._x",new byte[]{1})));
        assertEquals("COMMITTED",run("2023-06").status());assertEquals("COMMITTED",run("2023-07").status());assertEquals(2,rows());assertEquals(1,fixture.gets.get());
        assertEquals(2,repo.status().coverage().size());
    }
    @Test void publicationDelayOverdueAndRetries()throws Exception {
        fixture.objects.clear();var waiting=run("2023-07");assertEquals("WAITING",waiting.status());assertNotNull(waiting.nextRetry());
        fixture.put("202307-citibike-tripdata.zip",Map.of("trips.csv",HEADER+ROW));fixture.failures.set(2);
        assertEquals("COMMITTED",run("2023-07").status());assertEquals(3,fixture.gets.get());
        assertEquals("OVERDUE",run("2023-05").status());assertTrue(db.isReady());
    }
    @Test void jerseyCityOptInAndAmbiguity()throws Exception {
        assertThrows(IngestionFailure.class,()->new ArchiveCatalog(LatestArchiveDownloader.forIngestion(new IngestionHttpLimits(cfg),fixture.uri()),cfg).discover(ArchiveCatalog.City.JC,YearMonth.of(2023,7)));
        fixture.put("202307-citibike-tripdata.csv.zip",Map.of("trips.csv",HEADER+ROW));assertEquals("ambiguous_source",run("2023-07").reason());
    }
    @Test void missingAndCorruptDownloadedArchivesNeverPublish()throws Exception {
        fixture.corruptResponse=true;assertEquals("FAILED",run("2023-07").status());assertFalse(db.isReady());
        try(var files=Files.list(root.resolve("archives"))){assertFalse(files.anyMatch(p->p.toString().endsWith(".part")));}
    }
    @Test void interruptedQueueRecoveryAndDuplicateRequests()throws Exception {
        var j=queue("2023-07");assertEquals(j.id(),queue("2023-07").id());repo.takeNext();repo.recover();assertEquals("QUEUED",repo.get(j.id()).status());
        var restored=repo.takeNext();assertEquals(2,restored.attempts());coordinator.run(restored);assertEquals("COMMITTED",repo.get(j.id()).status());
    }
    @Test void writerOwnershipAndQueueLimits()throws Exception {
        var settings=IngestionFixtures.settings(root,fixture.uri(),Map.of());var second=new DuckDbManager(settings.getConfigMapping(CitibikeConfig.class),new CitibikeMetrics(new SimpleMeterRegistry()));
        assertThrows(IllegalStateException.class,second::start);second.shutdown();assertFalse(db.isReady());
        assertThrows(com.bscllc.learning.citibike.validation.InvalidRequestException.class,()->coordinator.month("2023-08"));
        assertEquals(YearMonth.of(2023,7),coordinator.previousMonth());assertThrows(com.bscllc.learning.citibike.validation.InvalidRequestException.class,()->coordinator.city("JC"));
    }
    @Test void readSnapshotSeesAllOldRowsUntilAtomicCommit()throws Exception {
        run("2023-07");var captured=db.withMetadata(c->{var conn=((org.duckdb.DuckDBConnection)c).duplicate();conn.setAutoCommit(false);try(var s=conn.createStatement();var r=s.executeQuery("SELECT count(*) FROM rides")){r.next();assertEquals(1,r.getLong(1));}return conn;});
        try(captured) {
            fixture.put("202307-citibike-tripdata.zip",Map.of("a.csv",HEADER+ROW,"b.csv",HEADER+ROW.replace("R1,","R2,")));run("2023-07");
            try(var s=captured.createStatement();var r=s.executeQuery("SELECT count(*) FROM rides")){r.next();assertEquals(1,r.getLong(1));}
            try(var s=captured.createStatement();var r=s.executeQuery("SELECT sum(rows) FROM active_partitions")){r.next();assertEquals(1,r.getLong(1));}
            captured.commit();assertEquals(2,rows());assertEquals(2,db.withSnapshot(LoadReports::read).totalRows());
        }
    }
    @Test void failedCommitRollsBackRowsAndMetadata()throws Exception {
        run("2023-07");var fault=new MonthlyImporter(db,cfg,clock){@Override protected void beforeCommit(Connection c){throw new IngestionFailure("injected_failure");}};
        var co=new IngestionCoordinator(db,cfg,repo,fault,new IngestionMetrics(meters,cfg,db),clock);
        fixture.put("202307-citibike-tripdata.zip",Map.of("trips.csv",HEADER+ROW.replace("R1,","REVISED,")));var job=queue("2023-07");co.run(repo.takeNext());
        assertEquals("FAILED",repo.get(job.id()).status());assertEquals(1,rows());assertEquals(1,db.withSnapshot(LoadReports::read).totalRows());co.stop(null);
    }
    @Test void policyPreservesPairedMissingCoordinatesAndBoundaryOverlap()throws Exception {
        String crossing=ROW.replace("2023-07-01 08:00:00","2023-06-30 23:59:59.900").replace("2023-07-01 08:10:00","2023-07-01 00:00:00.100").replace("40.7,-73.9,",",,");
        fixture.put("202307-citibike-tripdata.zip",Map.of("boundary.csv","\uFEFF"+HEADER+crossing));
        assertEquals("COMMITTED",run("2023-07").status());assertEquals(1,rows());assertEquals(1,repo.status().coverage().getFirst().missingStartCoordinates());
        assertEquals(LocalDateTime.of(2023,6,30,23,59,59,900000000),db.withSnapshot(LoadReports::read).minimumStartedAt());
        fixture.put("202307-citibike-tripdata.zip",Map.of("unpaired.csv",HEADER+ROW.replace("40.7,-73.9,",",-73.9,")));
        assertEquals("invalid_data",run("2023-07").reason());assertEquals(1,rows());
    }
    @Test void explicitlyEnabledJerseyCityAndBoundedQueue()throws Exception {
        db.shutdown();meters.close();configure(Map.of("citibike.ingestion.include-jersey-city","true","citibike.ingestion.max-queued-jobs","1"));
        fixture.put("JC-202307-citibike-tripdata.csv.zip",Map.of("jc.csv",HEADER+ROW.replace("R1,","JC1,")));
        var job=repo.enqueue(ArchiveCatalog.City.JC,List.of(YearMonth.of(2023,7)),"manual",true).getFirst();
        assertThrows(DatabaseBusyException.class,()->repo.enqueue(ArchiveCatalog.City.NYC,List.of(YearMonth.of(2023,6)),"manual",true));
        coordinator.run(repo.takeNext());assertEquals("COMMITTED",repo.get(job.id()).status());assertEquals(1,rows());
    }
    @Test void previousMonthUsesNewYorkClockAndBackfillBounds()throws Exception {
        Clock boundary=Clock.fixed(Instant.parse("2023-08-01T00:30:00Z"),ZoneOffset.UTC);
        var c=new IngestionCoordinator(db,cfg,repo,importer,new IngestionMetrics(meters,cfg,db),boundary);
        assertEquals(YearMonth.of(2023,6),c.previousMonth());
        assertThrows(com.bscllc.learning.citibike.validation.InvalidRequestException.class,()->coordinator.enqueue("NYC","2020-01","2023-07","backfill"));c.stop(null);
    }

    @Test void explicitBundledBootstrapIsReplacedInsteadOfDuplicated()throws Exception {
        Path sample=root.resolve("sample.csv");Files.writeString(sample,HEADER+ROW);
        MonthlyImporter.bootstrap(db,List.of(new CsvResourceCatalog.CsvResource("sample.csv",sample.toUri().toURL())));db.activate();assertEquals(1,rows());
        fixture.put("202307-citibike-tripdata.zip",Map.of("full.csv",HEADER+ROW+ROW.replace("R1,","R2,")));
        assertEquals("COMMITTED",run("2023-07").status());assertEquals(2,rows());assertEquals(1,db.withSnapshot(LoadReports::read).filesLoaded());
    }
    @Test void retentionRequiresVerifiedBackupAndNeverDeletesActiveSource()throws Exception {
        var original=run("2023-07");String[] old=db.withMetadata(c->{try(var s=c.prepareStatement("SELECT archive,sha256 FROM archive_versions WHERE id=?")){s.setString(1,original.versionId());try(var r=s.executeQuery()){r.next();return new String[]{r.getString(1),r.getString(2)};}}});
        fixture.put("202307-citibike-tripdata.zip",Map.of("revision.csv",HEADER+ROW.replace("R1,","REVISION,")));run("2023-07");
        db.withWriter(c->{try(var s=c.prepareStatement("UPDATE archive_versions SET downloaded_at=? WHERE id=?")){s.setTimestamp(1,Timestamp.from(clock.instant().minus(Duration.ofDays(31))));s.setString(2,original.versionId());s.executeUpdate();}return null;});
        var cleaner=new ArchiveCacheCleaner(db,cfg,new com.fasterxml.jackson.databind.ObjectMapper(),clock);cleaner.clean();assertTrue(Files.exists(root.resolve("archives").resolve(old[0])));
        db.shutdown();Path backup=root.resolve("snapshot");Files.createDirectories(backup);Map<String,String> hashes=new HashMap<>();
        Path database=root.resolve("citibike.duckdb");Files.copy(database,backup.resolve("citibike.duckdb"));hashes.put("citibike.duckdb",DownloadStateStore.digest(database));
        Files.createDirectory(backup.resolve("archives"));try(var files=Files.list(root.resolve("archives"))){for(Path p:files.toList()){Files.copy(p,backup.resolve("archives").resolve(p.getFileName()));hashes.put("archives/"+p.getFileName(),DownloadStateStore.digest(p));}}
        var json=new com.fasterxml.jackson.databind.ObjectMapper();json.writeValue(backup.resolve("backup-manifest.json").toFile(),Map.of("sha256",hashes));
        json.writeValue(root.resolve("backup-receipt.json").toFile(),Map.of("backupDirectory",backup.toString(),"sha256",hashes));
        db.start();db.activate();cleaner.clean();assertFalse(Files.exists(root.resolve("archives").resolve(old[0])));assertEquals(1,rows());
        var active=repo.status().coverage().getFirst();assertEquals("2023-07",active.month());
        // An incomplete/corrupt backup must never authorize another deletion.
        Files.writeString(backup.resolve("citibike.duckdb"),"corrupt");cleaner.clean();assertEquals(1,rows());
    }

    @Test void existingStrictSchemaMigratesAndPolicyIsRevalidated()throws Exception {
        run("2023-07");db.withWriter(c->{try(var statement=c.createStatement()){
            statement.execute("ALTER TABLE rides ALTER COLUMN start_lat SET NOT NULL");statement.execute("ALTER TABLE rides ALTER COLUMN start_lng SET NOT NULL");
            statement.execute("ALTER TABLE source_csv DROP COLUMN missing_start_coordinates");statement.execute("ALTER TABLE ingestion_attempts DROP COLUMN trigger");
            statement.execute("DELETE FROM schema_versions WHERE version>=2");statement.execute("UPDATE active_partitions SET policy='strict-v1'");
        }return null;});db.shutdown();db.start();db.activate();assertEquals(1,rows());
        assertEquals("COMMITTED",run("2023-07").status());assertEquals(1,fixture.gets.get());assertEquals(1,rows());
        int schema=db.withMetadata(c->{try(var st=c.createStatement();var r=st.executeQuery("SELECT max(version) FROM schema_versions")){r.next();return r.getInt(1);}});assertEquals(3,schema);
    }
    @Test void quotedHeaderAndOversizedHeaderDiagnostics()throws Exception {
        String quoted=String.join(",",Arrays.stream(HEADER.trim().split(",")).map(s->"\""+s+"\"").toList())+"\n";
        fixture.put("202307-citibike-tripdata.zip",Map.of("quoted.csv",quoted+ROW));assertEquals("COMMITTED",run("2023-07").status());
        String large=java.util.stream.IntStream.range(0,300).mapToObj(i->UUID.randomUUID().toString()).collect(java.util.stream.Collectors.joining());
        fixture.put("202307-citibike-tripdata.zip",Map.of("huge.csv",large+"\n"+ROW));assertEquals("unsupported_schema",run("2023-07").reason());assertEquals(1,rows());
    }

    @Test void configuredHeaderOnlyMembersAreRetainedWithoutEmptyMonthPublication()throws Exception {
        db.shutdown();meters.close();configure(Map.of("citibike.csv.fail-on-empty","false"));
        fixture.put("202307-citibike-tripdata.zip",Map.of("empty.csv",HEADER,"valid.csv",HEADER+ROW));assertEquals("COMMITTED",run("2023-07").status());
        assertEquals(2,db.withSnapshot(LoadReports::read).filesLoaded());assertEquals(1,rows());
        fixture.put("202307-citibike-tripdata.zip",Map.of("empty.csv",HEADER));assertEquals("invalid_data",run("2023-07").reason());assertEquals(1,rows());
    }

    @Test void oneJobCatchUpBudgetDoesNotStarveEnabledJerseyCity()throws Exception {
        db.shutdown();meters.close();configure(Map.of("citibike.ingestion.include-jersey-city","true","citibike.ingestion.jobs-per-run","1"));
        class MovingClock extends Clock {
            Instant now=Instant.parse("2023-08-02T12:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return Clock.fixed(now,zone);}public Instant instant(){return now;}
        }
        var moving=new MovingClock();var repository=new IngestionRepository(db,cfg,moving);
        var c=new IngestionCoordinator(db,cfg,repository,new MonthlyImporter(db,cfg,moving),new IngestionMetrics(meters,cfg,db),moving);
        try {
            for(int day=0;day<6;day++) {
                c.catchUp();long timeout=System.nanoTime()+Duration.ofSeconds(3).toNanos();
                while(System.nanoTime()<timeout && (repository.hasEligible()||!repository.jobs("RUNNING",1,0).isEmpty()))Thread.sleep(10);
                moving.now=moving.now.plus(Duration.ofDays(1));
            }
            Set<String> requests=new HashSet<>();for(var job:repository.jobs(null,100,0))requests.add(job.city()+"/"+job.month());
            for(String city:List.of("NYC","JC"))for(String month:List.of("2023-05","2023-06","2023-07"))assertTrue(requests.contains(city+"/"+month),city+month);
        }finally{c.stop(null);}
    }

    @Test void revisedValidatorsAndUnchangedAnnualMonthDoNotRepublishRows()throws Exception {
        var initial=run("2023-07");fixture.etags.put("202307-citibike-tripdata.zip","revised-validator");
        assertEquals("UNCHANGED",run("2023-07").status());assertEquals(initial.id(),db.withMetadata(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT job_id FROM active_partitions")){r.next();return r.getString(1);}}));
        fixture.objects.clear();byte[] july=zipStrings(Map.of("july.csv",HEADER+ROW.replace("R1,","ANNUAL,")));
        fixture.objects.put("2023-citibike-tripdata.zip",zip(Map.of("202307-citibike-tripdata.zip",july,"202306-citibike-tripdata.zip",zipStrings(Map.of("june.csv",HEADER+ROW.replace("R1,","JUNE,").replace("2023-07-01","2023-06-01"))))));
        fixture.etags.put("2023-citibike-tripdata.zip","annual1");assertEquals("COMMITTED",run("2023-07").status());
        fixture.objects.put("2023-citibike-tripdata.zip",zip(Map.of("202307-citibike-tripdata.zip",july,"202306-citibike-tripdata.zip",zipStrings(Map.of("june.csv",HEADER+ROW.replace("R1,","CHANGED,").replace("2023-07-01","2023-06-01"))))));
        fixture.etags.put("2023-citibike-tripdata.zip","annual2");assertEquals("UNCHANGED",run("2023-07").status());assertEquals(1,rows());
    }

}

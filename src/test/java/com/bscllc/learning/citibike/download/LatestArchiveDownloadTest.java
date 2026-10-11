package com.bscllc.learning.citibike.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.io.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class LatestArchiveDownloadTest {
    @TempDir Path directory;
    HttpServer server;
    byte[] bytes;
    String etag="\"v1\"", listing;
    AtomicInteger gets=new AtomicInteger(), failures=new AtomicInteger(), lists=new AtomicInteger();
    boolean badChecksum, omitValidator, truncate, headMissing;
    int responseOverride;
    String version;
    AtomicReference<String> getQuery=new AtomicReference<>(), getIfMatch=new AtomicReference<>();
    java.util.concurrent.CountDownLatch entered, release;
    String secondPage;
    Map<String,Object> overrides=new HashMap<>();
    DownloadConfig cfg;
    @BeforeEach void setup() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try (ZipOutputStream z=new ZipOutputStream(out)) { z.putNextEntry(new ZipEntry("trips.csv")); z.write("ride_id\nsmall-fixture\n".getBytes()); z.closeEntry(); }
        bytes=out.toByteArray();
        cfg=(DownloadConfig)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DownloadConfig.class},(p,m,a)-> { if(overrides.containsKey(m.getName()))return overrides.get(m.getName()); return switch(m.getName()) {
            case "enabled","checkOnStartup" -> true;
            case "dataDirectory" -> directory.toString();
            case "schedule" -> "off";
            case "timeZone" -> "America/New_York";
            case "connectTimeout","readTimeout" -> Duration.ofMillis(500);
            case "operationTimeout" -> Duration.ofSeconds(5);
            case "maxBackoff","cleanupGracePeriod" -> Duration.ZERO;
            case "maxAttempts" -> 3;
            case "maxRediscoveries" -> 2;
            case "maxListingPages" -> 4;
            case "retainedGenerations" -> 2;
            case "retainedAttempts" -> 100;
            case "maxDownloadBytes","maxListingResponseBytes" -> 100000L;
            case "minFreeDiskBytes" -> 0L;
            default -> throw new UnsupportedOperationException(m.getName());
        }; });
        listing=xml(false,null,object("202609-citibike-tripdata.zip"));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/tripdata",exchange->{
            try {
                if(responseOverride!=0) { exchange.sendResponseHeaders(responseOverride,-1); return; }
                if (exchange.getRequestURI().getPath().equals("/tripdata")) {
                    lists.incrementAndGet(); String body=secondPage!=null && exchange.getRequestURI().getQuery().contains("continuation-token=")?secondPage:listing;
                    byte[] b=body.getBytes(); exchange.sendResponseHeaders(200,b.length); exchange.getResponseBody().write(b);
                } else if (exchange.getRequestMethod().equals("HEAD")) {
                    exchange.getResponseHeaders().add("Content-Length",Integer.toString(bytes.length));
                    if (!omitValidator) exchange.getResponseHeaders().add("ETag",etag);
                    if(version!=null)exchange.getResponseHeaders().add("x-amz-version-id",version);
                    exchange.sendResponseHeaders(headMissing?404:200,-1);
                } else {
                    gets.incrementAndGet();
                    getQuery.set(exchange.getRequestURI().getRawQuery());getIfMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
                    if(entered!=null) { entered.countDown(); try { release.await(2,java.util.concurrent.TimeUnit.SECONDS); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); } }
                    if (failures.getAndUpdate(v->Math.max(0,v-1))>0) { exchange.sendResponseHeaders(503,-1); }
                    else {
                        if (!omitValidator) exchange.getResponseHeaders().add("ETag",etag);
                        if(version!=null)exchange.getResponseHeaders().add("x-amz-version-id",version);
                        if (badChecksum) { exchange.getResponseHeaders().add("x-amz-checksum-sha256","bad"); exchange.getResponseHeaders().add("x-amz-checksum-type","FULL_OBJECT"); }
                        exchange.sendResponseHeaders(200,bytes.length);
                        exchange.getResponseBody().write(bytes,0,truncate?bytes.length/2:bytes.length);
                    }
                }
            } finally { exchange.close(); }
        }); server.start();
    }
    @AfterEach void close() { if(server!=null)server.stop(0); }
    String object(String key) { return "<Contents><Key>"+key+"</Key><Size>"+bytes.length+"</Size><ETag>&quot;v1&quot;</ETag><LastModified>1990-01-01T00:00:00Z</LastModified></Contents>"; }
    String xml(boolean more,String token,String objects) { return "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><EncodingType>url</EncodingType>"+objects+"<IsTruncated>"+more+"</IsTruncated>"+(token==null?"":"<NextContinuationToken>"+token+"</NextContinuationToken>")+"</ListBucketResult>"; }
    LatestArchiveDownloader http() { return new LatestArchiveDownloader(cfg,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/tripdata")); }
    DownloadCoordinator coordinator() { var c=new DownloadCoordinator(); c.config=cfg; c.json=new ObjectMapper(); return c; }
    DownloadStateStore state() throws Exception { return new DownloadStateStore(directory,new ObjectMapper()); }
    @Test void paginatedStrictGreatestMonthIndependentOfClock() throws Exception {
        listing=xml(true,"opaque+/=",object("202601-citibike-tripdata.zip")+object("JC-209901-citibike-tripdata.zip")+object("209913-citibike-tripdata.zip")+object("000001-citibike-tripdata.zip")+object("209912-citibike-tripdata.csv.zip")+object("2099-citibike-tripdata.zip"));
        secondPage=xml(false,null,object("209912-citibike-tripdata.zip")+object("folder%2F299912-citibike-tripdata.zip"));
        var h=http();h.begin();assertEquals("209912",h.discover().month());assertEquals(2,lists.get());assertEquals(0,gets.get());
    }
    @Test void publicationAndIdempotencySurviveRestart() throws Exception {
        assertEquals("SUCCEEDED",coordinator().execute(http()));
        var manifest=state().active(); assertTrue(state().valid(manifest)); assertEquals(bytes.length,manifest.size());
        assertEquals("UNCHANGED",coordinator().execute(http()));assertEquals(1,gets.get());
        assertEquals(manifest.archive(),state().active().archive());
    }
    @Test void correctionAndNewMonthPublishWholeGeneration() throws Exception {
        var c=coordinator(); assertEquals("SUCCEEDED",c.execute(http()));String old=state().active().archive();
        bytes="corrected opaque archive".getBytes();etag="\"v2\"";
        assertEquals("SUCCEEDED",c.execute(http()));assertNotEquals(old,state().active().archive());assertTrue(Files.exists(directory.resolve(old)));
        listing=xml(false,null,object("202610-citibike-tripdata.zip")+object("202609-citibike-tripdata.zip"));
        assertEquals("SUCCEEDED",c.execute(http()));assertEquals("202610",state().active().month());assertEquals(3,gets.get());
    }
    @Test void revisedValidatorWithSameBytesReusesGeneration() throws Exception {
        var c=coordinator();c.execute(http());String old=state().active().archive();etag="\"v2\"";
        assertEquals("SUCCEEDED",c.execute(http()));assertEquals(old,state().active().archive());assertEquals(etag,state().active().etag());
    }
    @Test void publicationDelayAndNoMatchPreserveSuccess() throws Exception {
        var c=coordinator();listing=xml(false,null,"");assertEquals("NO_MATCH",c.execute(http()));assertNull(state().active());
        listing=xml(false,null,object("202609-citibike-tripdata.zip"));assertEquals("SUCCEEDED",c.execute(http()));
        listing=xml(false,null,"");assertEquals("NO_MATCH",c.execute(http()));assertTrue(state().valid(state().active()));
    }
    @Test void transientRetryAndCorruptionPreservePriorCommit() throws Exception {
        failures.set(2);var c=coordinator();assertEquals("SUCCEEDED",c.execute(http()));assertEquals(3,gets.get());
        String prior=state().active().checksum();etag="\"changed\"";badChecksum=true;
        assertEquals("FAILED",c.execute(http()));assertEquals("CHECKSUM_MISMATCH",c.status().reason());assertEquals(prior,state().active().checksum());
        try(var f=Files.list(directory)) { assertFalse(f.anyMatch(p->p.toString().endsWith(".part"))); }
    }
    @Test void malformedAndUnsafeXmlAndPaginationFailClosed() throws Exception {
        for(String body:List.of("<bad>","<!DOCTYPE x [<!ENTITY ext SYSTEM 'file:///etc/passwd'>]><ListBucketResult>&ext;</ListBucketResult>",xml(true,null,object("202609-citibike-tripdata.zip")),xml(true,"repeated",object("202609-citibike-tripdata.zip")))) {
            listing=body;assertEquals("FAILED",coordinator().execute(http()));assertEquals(0,gets.get());
        }
    }
    @Test void changedMissingObjectHasBoundedRediscoveryAndNoFallback() {
        headMissing=true;listing=xml(false,null,object("202609-citibike-tripdata.zip")+object("202608-citibike-tripdata.zip"));
        assertEquals("FAILED",coordinator().execute(http()));assertEquals(3,lists.get());assertEquals(0,gets.get());
    }
    @Test void truncatedDownloadRetriesAndLeavesNoPublication() throws Exception {
        truncate=true;assertEquals("FAILED",coordinator().execute(http()));assertTrue(gets.get()>=3 && gets.get()<=6);assertNull(state().active());
    }
    @Test void missingValidatorsAndCorruptLocalFileRequireRedownload() throws Exception {
        omitValidator=true;var c=coordinator();c.execute(http());assertEquals("SUCCEEDED",c.execute(http()));assertEquals(2,gets.get());
        omitValidator=false;c.execute(http());Files.writeString(directory.resolve(state().active().archive()),"broken");
        assertEquals("SUCCEEDED",c.execute(http()));assertTrue(state().valid(state().active()));
    }
    @Test void lockConflictDoesNotTouchActiveState() throws Exception {
        var c=coordinator();c.execute(http());
        try(var f=FileChannel.open(directory.resolve("owner.lock"),StandardOpenOption.WRITE);var lock=f.lock()) {
            assertEquals("BUSY",coordinator().execute(http()));assertEquals(1,gets.get());
        }
    }
    @Test void interruptedAndCommittedAttemptsRecoverAndPartialFilesDisappear() throws Exception {
        var c=coordinator();c.execute(http());var s=state();var m=s.active();
        s.attempt(new DownloadStateStore.Attempt(m.attemptId(),m.key(),m.discoveredAt(),null,"DOWNLOADING",null,0));
        String id=UUID.randomUUID().toString();s.attempt(new DownloadStateStore.Attempt(id,null,"now",null,"DISCOVERING",null,0));
        Files.writeString(directory.resolve("download-stale.part"),"partial");s.recover();
        assertFalse(Files.exists(directory.resolve("download-stale.part")));
        assertTrue(Files.readString(directory.resolve("attempt-"+id+".json")).contains("INTERRUPTED"));
        assertTrue(Files.readString(directory.resolve("attempt-"+m.attemptId()+".json")).contains("SUCCEEDED"));
    }
    @Test void readersSeeOnlyCompleteManifestsAndBackupRestores() throws Exception {
        coordinator().execute(http());var s=state();var m=s.active();
        AtomicBoolean done=new AtomicBoolean();AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread reader=new Thread(()->{while(!done.get())try{assertTrue(s.valid(s.active()));}catch(Throwable e){failure.set(e);}});reader.start();
        try { for(int i=0;i<20;i++)s.publish(m); }finally{done.set(true);reader.join();}
        assertNull(failure.get());Path backup=directory.resolve("backup");Files.createDirectory(backup);
        Files.copy(directory.resolve("active.json"),backup.resolve("active.json"));Files.copy(directory.resolve(m.archive()),backup.resolve(m.archive()));
        var restored=new DownloadStateStore(backup,new ObjectMapper());assertTrue(restored.valid(restored.active()));
    }
    @Test void resourceLimitsFailWithoutPublishing() throws Exception {
        overrides.put("maxDownloadBytes",1L);
        assertEquals("FAILED",coordinator().execute(http()));assertEquals(0,gets.get());assertNull(state().active());
        overrides.clear();overrides.put("maxListingResponseBytes",1L);
        assertEquals("FAILED",coordinator().execute(http()));assertEquals(0,gets.get());
        overrides.clear();overrides.put("maxListingPages",1);
        listing=xml(true,"next",object("202609-citibike-tripdata.zip"));
        assertEquals("FAILED",coordinator().execute(http()));assertEquals(0,gets.get());
        overrides.clear();overrides.put("minFreeDiskBytes",Long.MAX_VALUE);
        listing=xml(false,null,object("202609-citibike-tripdata.zip"));
        assertEquals("FAILED",coordinator().execute(http()));assertEquals(0,gets.get());
    }
    @Test void retriesAreBoundedAndFailureRetainsLastSuccess() throws Exception {
        var c=coordinator();c.execute(http());String checksum=state().active().checksum();
        etag="\"new\"";failures.set(20);
        assertEquals("FAILED",c.execute(http()));assertEquals(4,gets.get());assertEquals(checksum,state().active().checksum());
    }
    @Test void retentionAndCorruptStateRecoveryAreBounded() throws Exception {
        var c=coordinator();c.execute(http());var s=state();String current=s.active().archive();
        overrides.put("retainedAttempts",2);overrides.put("retainedGenerations",1);
        for(int i=0;i<5;i++)c.execute(http());
        Files.writeString(directory.resolve("attempt-"+UUID.randomUUID()+".json"),"broken");
        Files.writeString(directory.resolve("archive-"+UUID.randomUUID()+".zip"),"old");
        s.recover();s.cleanup(cfg);
        assertTrue(Files.exists(directory.resolve(current)));
        try(var f=Files.list(directory)){assertTrue(f.filter(p->p.getFileName().toString().startsWith("attempt-")).count()<=2);}
        Files.writeString(directory.resolve("active.json"),"broken");assertEquals("SUCCEEDED",c.execute(http()));assertTrue(s.valid(s.active()));
    }
    @Test void metricsUseOnlyFixedOutcomesAndStatusIsSanitized() throws Exception {
        var c=coordinator();c.metrics=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        assertEquals("SUCCEEDED",c.execute(http()));assertEquals("202609",c.status().lastSuccessfulMonth());
        listing="bad XML with private data";assertEquals("FAILED",c.execute(http()));
        assertEquals("INVALID_LISTING",c.status().reason());assertEquals("202609",c.status().lastSuccessfulMonth());
        for(var meter:c.metrics.getMeters())for(var tag:meter.getId().getTags())assertEquals("outcome",tag.getKey());
    }

    @Test void overlappingChecksAreSkippedAndStatusReportsCurrentAttempt() throws Exception {
        entered=new java.util.concurrent.CountDownLatch(1);release=new java.util.concurrent.CountDownLatch(1);
        overrides.put("readTimeout",Duration.ofSeconds(3));var c=coordinator();
        var result=new AtomicReference<String>();Thread worker=new Thread(()->result.set(c.execute(http())));worker.start();
        try {
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("DOWNLOADING",c.status().outcome());assertNotNull(c.status().currentAttempt());
            assertEquals("BUSY",c.execute(http()));
        } finally { release.countDown();worker.join(5000); }
        assertEquals("SUCCEEDED",result.get());assertNull(c.status().currentAttempt());assertEquals(1,gets.get());
    }

    @Test void versionAndEtagPinningUseUpstreamValidators() throws Exception {
        var c=coordinator();assertEquals("SUCCEEDED",c.execute(http()));assertEquals(etag,getIfMatch.get());
        version="v+/=";assertEquals("SUCCEEDED",c.execute(http()));
        assertEquals(version,state().active().version());assertTrue(getQuery.get().contains("versionId=v%2B%2F%3D"));assertNull(getIfMatch.get());
        assertEquals("UNCHANGED",c.execute(http()));assertEquals(2,gets.get());
    }
    @Test void permanentFailuresAndRedirectsNeverRetryOrFollow() {
        for(int code:List.of(403,302)) {
            responseOverride=code;assertEquals("FAILED",coordinator().execute(http()));assertEquals(0,gets.get());
        }
    }
    @Test void readTimeoutAndWholeOperationDeadlineTerminate() throws Exception {
        overrides.put("maxAttempts",1);overrides.put("readTimeout",Duration.ofMillis(20));
        entered=new java.util.concurrent.CountDownLatch(1);release=new java.util.concurrent.CountDownLatch(1);
        try { assertEquals("FAILED",coordinator().execute(http())); } finally { release.countDown(); }
        assertNull(state().active());
        overrides.put("operationTimeout",Duration.ofNanos(1));var h=http();h.begin();
        assertEquals("DEADLINE",assertThrows(java.io.IOException.class,h::discover).getMessage());
    }

}

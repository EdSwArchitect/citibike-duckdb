package com.bscllc.learning.citibike.ingestion;

import com.bscllc.learning.citibike.db.*;
import com.bscllc.learning.citibike.config.CitibikeConfig;
import com.bscllc.learning.citibike.metrics.CitibikeMetrics;
import com.bscllc.learning.citibike.repository.*;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import java.net.*;
import java.time.*;
import java.util.*;

/** Explicit opt-in full archive smoke; streams a previously discovered/downloaded source fixture. */
public class FullIngestionMain {
    public static void main(String[] args)throws Exception {
        Path archive=Path.of(args[0]),root=Path.of(args[2]);YearMonth month=YearMonth.parse(args[1]);long bytes=Files.size(archive);
        String key=month.toString().replace("-","")+"-citibike-tripdata.zip";
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/tripdata",e->{try {
            if(e.getRequestURI().getPath().equals("/tripdata")) {
                byte[] body=("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Contents><Key>"+key+"</Key><Size>"+bytes+"</Size><ETag>profile</ETag></Contents><IsTruncated>false</IsTruncated></ListBucketResult>").getBytes();
                e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);
            }else {
                e.getResponseHeaders().add("ETag","profile");
                if(e.getRequestMethod().equals("HEAD")){e.getResponseHeaders().add("Content-Length",Long.toString(bytes));e.sendResponseHeaders(200,-1);}
                else{e.sendResponseHeaders(200,bytes);Files.copy(archive,e.getResponseBody());}
            }
        }finally{e.close();}});server.start();
        var settings=IngestionFixtures.settings(root,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/tripdata"),Map.of(
            "citibike.ingestion.max-expanded-bytes","17179869184","citibike.ingestion.max-entry-bytes","4294967296",
            "citibike.ingestion.min-free-disk-bytes","8589934592","citibike.duckdb.max-temp-directory-size","8GB"));
        var cfg=settings.getConfigMapping(IngestionConfig.class);var registry=new SimpleMeterRegistry();var metrics=new CitibikeMetrics(registry);
        var manager=new DuckDbManager(settings.getConfigMapping(CitibikeConfig.class),metrics);Clock clock=Clock.systemUTC();
        manager.start();manager.activate(false);Files.createDirectories(root.resolve("jobs"));Files.createDirectories(root.resolve("archives"));
        var jobs=new IngestionRepository(manager,cfg,clock);var coordinator=new IngestionCoordinator(manager,cfg,jobs,new MonthlyImporter(manager,cfg,clock),new IngestionMetrics(registry,cfg,manager),clock);
        var peakRuntime=new java.util.concurrent.atomic.AtomicLong();var peakSpill=new java.util.concurrent.atomic.AtomicLong();
        var sampler=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(()->{try(var files=Files.walk(root)) {
            long total=0,spill=0;for(Path file:files.filter(Files::isRegularFile).toList()) {
                long size=Files.size(file);total+=size;if(root.relativize(file).toString().startsWith("spill-"))spill+=size;
            }
            peakRuntime.accumulateAndGet(total,Math::max);peakSpill.accumulateAndGet(spill,Math::max);
        }catch(Exception ignored){}},0,200,java.util.concurrent.TimeUnit.MILLISECONDS);
        long started=System.nanoTime();
        try {
            var job=jobs.enqueue(ArchiveCatalog.City.NYC,List.of(month),"manual",true).getFirst();coordinator.run(jobs.takeNext());job=jobs.get(job.id());
            System.out.println("import_status="+job.status());System.out.println("import_reason="+job.reason());System.out.println("import_rows="+job.rows());
            System.out.println("import_seconds="+((System.nanoTime()-started)/1_000_000_000.0));
            if(!job.status().equals("COMMITTED"))throw new IllegalStateException("Full import did not commit");
            var repo=new RideAnalyticsRepository(manager,metrics);var report=manager.withSnapshot(LoadReports::read);
            System.out.println("files="+report.filesLoaded());System.out.println("coverage="+jobs.status().coverage().getFirst());
            long queryStart=System.nanoTime();var summary=repo.summary(new RideFilter(report.minimumStartedAt().toLocalDate(),report.maximumStartedAt().toLocalDate(),null,null));
            System.out.println("summary_rows="+summary.rideCount());System.out.println("summary_ms="+((System.nanoTime()-queryStart)/1_000_000.0));
            queryStart=System.nanoTime();var quality=repo.dataQualitySnapshot(report);
            System.out.println("invalid_rows="+quality.stats().invalidRows());System.out.println("quality_ms="+((System.nanoTime()-queryStart)/1_000_000.0));
            var repeated=jobs.enqueue(ArchiveCatalog.City.NYC,List.of(month),"manual",true).getFirst();coordinator.run(jobs.takeNext());System.out.println("recheck_status="+jobs.get(repeated.id()).status());
        }finally{sampler.shutdownNow();coordinator.stop(null);manager.shutdown();registry.close();server.stop(0);}
        System.out.println("sampled_peak_runtime_file_bytes="+peakRuntime.get());System.out.println("sampled_peak_spill_bytes="+peakSpill.get());
        try(var files=Files.walk(root)){System.out.println("retained_disk_bytes="+files.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(Exception e){return 0;}}).sum());}
    }
}

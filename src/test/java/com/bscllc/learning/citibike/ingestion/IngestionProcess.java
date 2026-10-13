package com.bscllc.learning.citibike.ingestion;

import com.bscllc.learning.citibike.config.CitibikeConfig;
import com.bscllc.learning.citibike.db.*;
import com.bscllc.learning.citibike.metrics.CitibikeMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.sql.*;

/** Test process loads runtime application classes from the packaged Quarkus artifact. */
public class IngestionProcess {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[1]);String action=args[0];
        var settings=IngestionFixtures.settings(root,URI.create(args.length>2?args[2]:"http://localhost/tripdata"),Map.of());
        var registry=new SimpleMeterRegistry();var manager=new DuckDbManager(settings.getConfigMapping(CitibikeConfig.class),new CitibikeMetrics(registry));
        try{manager.start();}catch(IllegalStateException conflict){System.out.println("START_FAILED");return;}
        try {
            manager.activate(false);Files.createDirectories(root.resolve("jobs"));Files.createDirectories(root.resolve("archives"));
            if(action.equals("hold")){System.out.println("OWNED");System.out.flush();Thread.sleep(30000);return;}
            if(action.equals("count")) {
                long rows=manager.withMetadata(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM rides")){r.next();return r.getLong(1);}});
                System.out.println("ROWS="+rows);return;
            }
            var cfg=settings.getConfigMapping(IngestionConfig.class);Clock clock=Clock.fixed(Instant.parse("2023-08-02T12:00:00Z"),ZoneOffset.UTC);
            var repo=new IngestionRepository(manager,cfg,clock);repo.recover();
            var importer=new MonthlyImporter(manager,cfg,clock){
                @Override protected void beforeCommit(Connection connection){if(action.equals("before"))Runtime.getRuntime().halt(17);}
                @Override protected void afterCommit(){if(action.equals("after"))Runtime.getRuntime().halt(18);}
            };
            var coordinator=new IngestionCoordinator(manager,cfg,repo,importer,new IngestionMetrics(registry,cfg,manager),clock);
            try {
                String month=args.length>3?args[3]:"2023-07";repo.enqueue(ArchiveCatalog.City.NYC,List.of(YearMonth.parse(month)),"manual",true);
                var running=repo.takeNext();coordinator.run(running);System.out.println("RESULT="+repo.get(running.id()).status());
            }finally{coordinator.stop(null);}
        }finally{manager.shutdown();registry.close();}
    }
}

package com.bscllc.learning.citibike.ingestion;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.nio.file.*;
import java.util.*;

public class IngestionApiResource implements QuarkusTestResourceLifecycleManager {
    private IngestionFixtures fixture;private Path root;
    public Map<String,String> start(){try {
        fixture=new IngestionFixtures();root=Files.createTempDirectory("citibike-api-ingestion-");
        fixture.put("202307-citibike-tripdata.zip",Map.of("one.csv",IngestionFixtures.HEADER+IngestionFixtures.ROW));
        return Map.of("citibike.ingestion.source-uri",fixture.uri().toString(),"citibike.storage.data-directory",root.toString());
    }catch(Exception e){throw new RuntimeException("Cannot start local ingestion fixtures");}}
    public void stop(){if(fixture!=null)fixture.close();if(root!=null)MonthlyImporter.deleteDirectory(root);}
}

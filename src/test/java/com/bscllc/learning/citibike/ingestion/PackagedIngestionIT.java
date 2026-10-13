package com.bscllc.learning.citibike.ingestion;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PackagedIngestionIT {
    @TempDir Path root;
    Process process(String action,Path data,String url,String month)throws Exception {
        List<String> cp=new ArrayList<>();cp.add("target/test-classes");try(var files=Files.walk(Path.of("target/quarkus-app"))){files.filter(p->p.toString().endsWith(".jar")).forEach(p->cp.add(p.toAbsolutePath().toString()));}
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"--enable-native-access=ALL-UNNAMED","-cp",String.join(java.io.File.pathSeparator,cp),IngestionProcess.class.getName(),action,data.toString(),url,month).redirectErrorStream(true).start();
    }
    String run(String action,Path data,String url,String month,int code)throws Exception {
        Process p=process(action,data,url,month);try{assertTrue(p.waitFor(30,TimeUnit.SECONDS));String out=new String(p.getInputStream().readAllBytes());assertEquals(code,p.exitValue(),out);return out;}finally{p.destroyForcibly();}
    }
    @Test void fileRestartWalAtomicityCorrectionsAndProcessOwnership()throws Exception {
        Path data=root.resolve("data");
        try(var stub=new IngestionFixtures()) {
            stub.put("202307-citibike-tripdata.zip",Map.of("trips.csv",IngestionFixtures.HEADER+IngestionFixtures.ROW));String url=stub.uri().toString();
            assertTrue(run("import",data,url,"2023-07",0).contains("RESULT=COMMITTED"));assertTrue(run("count",data,url,"2023-07",0).contains("ROWS=1"));
            assertTrue(run("import",data,url,"2023-07",0).contains("RESULT=UNCHANGED"));assertEquals(1,stub.gets.get());
            stub.put("202307-citibike-tripdata.zip",Map.of("trips.csv",IngestionFixtures.HEADER+IngestionFixtures.ROW+IngestionFixtures.ROW.replace("R1,","R2,")));
            run("before",data,url,"2023-07",17);assertTrue(run("count",data,url,"2023-07",0).contains("ROWS=1"));
            assertTrue(run("import",data,url,"2023-07",0).contains("RESULT=COMMITTED"));assertTrue(run("count",data,url,"2023-07",0).contains("ROWS=2"));
            stub.put("202307-citibike-tripdata.zip",Map.of("trips.csv",IngestionFixtures.HEADER+IngestionFixtures.ROW.replace("R1,","REVISED,")));
            run("after",data,url,"2023-07",18);assertTrue(run("count",data,url,"2023-07",0).contains("ROWS=1"));
            assertTrue(run("import",data,url,"2023-07",0).contains("RESULT=UNCHANGED"));
            stub.put("202306-citibike-tripdata.zip",Map.of("june.csv",IngestionFixtures.HEADER+IngestionFixtures.ROW.replace("R1,","JUNE,").replace("2023-07-01","2023-06-01")));
            assertTrue(run("import",data,url,"2023-06",0).contains("RESULT=COMMITTED"));assertTrue(run("count",data,url,"2023-07",0).contains("ROWS=2"));
            Path backup=root.resolve("backup");Files.createDirectories(backup);
            try(var files=Files.walk(data)){for(Path p:files.toList()){Path dest=backup.resolve(data.relativize(p));if(Files.isDirectory(p))Files.createDirectories(dest);else Files.copy(p,dest);}}
            assertTrue(run("count",backup,url,"2023-07",0).contains("ROWS=2"));
            Process owner=process("hold",data,url,"2023-07");try(var in=new java.io.BufferedReader(new java.io.InputStreamReader(owner.getInputStream()))) {
                String line;do{line=in.readLine();}while(line!=null&&!line.equals("OWNED"));assertEquals("OWNED",line);
                assertTrue(run("count",data,url,"2023-07",0).contains("START_FAILED"));
            }finally{owner.destroyForcibly();owner.waitFor(5,TimeUnit.SECONDS);}
        }
    }
}

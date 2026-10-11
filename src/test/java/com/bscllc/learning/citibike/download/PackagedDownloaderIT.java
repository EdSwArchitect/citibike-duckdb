package com.bscllc.learning.citibike.download;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PackagedDownloaderIT {
    @TempDir Path root;
    Process process(String endpoint) throws Exception {
        List<String> cp=new ArrayList<>();cp.add("target/test-classes");
        try(var files=Files.walk(Path.of("target/quarkus-app"))) {
            files.filter(p->p.toString().endsWith(".jar")).forEach(p->cp.add(p.toAbsolutePath().toString()));
        }
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",String.join(java.io.File.pathSeparator,cp),DownloaderProcess.class.getName(),endpoint,root.toString()).redirectErrorStream(true).start();
    }
    String run(String endpoint,int expected) throws Exception {
        Process p=process(endpoint);
        try { assertTrue(p.waitFor(20,TimeUnit.SECONDS));String out=new String(p.getInputStream().readAllBytes());assertEquals(expected,p.exitValue(),out);return out; }
        finally { p.destroyForcibly(); }
    }
    @Test void packagedDownloadRestartCrashRecoveryAndProcessLock() throws Exception {
        byte[] data="small opaque ZIP fixture".getBytes();AtomicInteger gets=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/tripdata",e->{
            try {
                if(e.getRequestURI().getPath().equals("/tripdata")) {
                    byte[] b=("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Contents><Key>202609-citibike-tripdata.zip</Key><Size>"+data.length+"</Size></Contents><IsTruncated>false</IsTruncated></ListBucketResult>").getBytes();
                    e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);
                }else {
                    e.getResponseHeaders().add("ETag","\"fixture\"");
                    if(e.getRequestMethod().equals("HEAD")){e.getResponseHeaders().add("Content-Length",Integer.toString(data.length));e.sendResponseHeaders(200,-1);}
                    else{gets.incrementAndGet();e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);}
                }
            }finally{e.close();}
        });server.start();
        try {
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/tripdata";
            assertTrue(run(endpoint,0).contains("RESULT=SUCCEEDED"));
            assertTrue(run(endpoint,0).contains("RESULT=UNCHANGED"));assertEquals(1,gets.get());
            run("crash",17);assertTrue(Files.exists(root.resolve("download-crashed.part")));
            assertTrue(run(endpoint,0).contains("RESULT=UNCHANGED"));assertFalse(Files.exists(root.resolve("download-crashed.part")));
            Process owner=process("lock");
            try {
                try(var in=new java.io.BufferedReader(new java.io.InputStreamReader(owner.getInputStream()))) {
                    assertEquals("LOCKED",in.readLine());assertTrue(run(endpoint,0).contains("RESULT=BUSY"));
                }
            }finally{owner.destroyForcibly();owner.waitFor(5,TimeUnit.SECONDS);}
        }finally{server.stop(0);}
    }
}

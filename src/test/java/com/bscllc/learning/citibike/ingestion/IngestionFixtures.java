package com.bscllc.learning.citibike.ingestion;

import com.bscllc.learning.citibike.config.CitibikeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.io.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;

public final class IngestionFixtures implements AutoCloseable {
    public static final String HEADER=com.bscllc.learning.citibike.support.TestSupport.HEADER;
    public static final String ROW=com.bscllc.learning.citibike.support.TestSupport.ROW;
    public HttpServer server;
    public final Map<String,byte[]> objects=new java.util.concurrent.ConcurrentHashMap<>();
    public final Map<String,String> etags=new java.util.concurrent.ConcurrentHashMap<>();
    public final AtomicInteger gets=new AtomicInteger(),failures=new AtomicInteger();
    public boolean truncatedListing;public boolean corruptResponse;
    public IngestionFixtures()throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/tripdata",e->{try {
            String path=e.getRequestURI().getPath();
            if(path.equals("/tripdata")) {
                String prefix="";for(String p:e.getRequestURI().getQuery().split("&"))if(p.startsWith("prefix="))prefix=URLDecoder.decode(p.substring(7),java.nio.charset.StandardCharsets.UTF_8);
                StringBuilder xml=new StringBuilder("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><EncodingType>url</EncodingType>");
                for(var object:objects.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList())if(object.getKey().startsWith(prefix))
                    xml.append("<Contents><Key>").append(object.getKey()).append("</Key><Size>").append(object.getValue().length).append("</Size><ETag>").append(etags.getOrDefault(object.getKey(),"v1")).append("</ETag></Contents>");
                xml.append("<IsTruncated>").append(truncatedListing).append("</IsTruncated></ListBucketResult>");byte[] b=xml.toString().getBytes();e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);
            }else {
                String key=path.substring("/tripdata/".length());byte[] data=objects.get(key);
                if(data==null){e.sendResponseHeaders(404,-1);return;}
                e.getResponseHeaders().add("ETag",etags.getOrDefault(key,"v1"));
                if(e.getRequestMethod().equals("HEAD")){e.getResponseHeaders().add("Content-Length",Integer.toString(data.length));e.sendResponseHeaders(200,-1);}
                else {
                    gets.incrementAndGet();if(failures.getAndUpdate(n->Math.max(0,n-1))>0){e.sendResponseHeaders(503,-1);return;}
                    String match=e.getRequestHeaders().getFirst("If-Match");if(match!=null&&!match.equals(etags.getOrDefault(key,"v1"))){e.sendResponseHeaders(412,-1);return;}
                    e.sendResponseHeaders(200,data.length);e.getResponseBody().write(corruptResponse?Arrays.copyOf(data,data.length/2):data);
                }
            }
        }finally{e.close();}});server.start();
    }
    public URI uri(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/tripdata");}
    public void put(String key,Map<String,String> csvs)throws IOException{objects.put(key,zipStrings(csvs));etags.put(key,UUID.randomUUID().toString());}
    public static byte[] zipStrings(Map<String,String> files)throws IOException {Map<String,byte[]> bytes=new LinkedHashMap<>();files.forEach((k,v)->bytes.put(k,v.getBytes(java.nio.charset.StandardCharsets.UTF_8)));return zip(bytes);}
    public static byte[] zip(Map<String,byte[]> files)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){
            for(var file:files.entrySet()){zip.putNextEntry(new ZipEntry(file.getKey()));zip.write(file.getValue());zip.closeEntry();}
        }return bytes.toByteArray();
    }
    public static io.smallrye.config.SmallRyeConfig settings(Path root,URI uri,Map<String,String> overrides){
        var b=new SmallRyeConfigBuilder().withMapping(CitibikeConfig.class).withMapping(IngestionConfig.class)
            .withConverter(Duration.class,100,new io.quarkus.runtime.configuration.DurationConverter());
        Map<String,String> values=new HashMap<>(Map.of("citibike.storage.mode","file","citibike.storage.data-directory",root.toString(),
            "citibike.ingestion.source-uri",uri.toString(),"citibike.ingestion.min-free-disk-bytes","0",
            "citibike.ingestion.max-expanded-bytes","1048576","citibike.ingestion.max-entry-bytes","1048576",
            "citibike.ingestion.max-backoff","0s","citibike.ingestion.check-on-startup","false",
            "citibike.ingestion.monthly-cron","off","citibike.ingestion.catch-up-cron","off"));
        values.putAll(overrides);values.forEach(b::withDefaultValue);return b.build();
    }
    @Override public void close(){server.stop(0);}
}

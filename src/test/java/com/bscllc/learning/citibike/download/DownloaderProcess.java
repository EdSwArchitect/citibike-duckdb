package com.bscllc.learning.citibike.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.*;
import java.nio.channels.*;
import java.time.Duration;

/** Subprocess harness uses only application classes/libraries from the packaged artifact. */
public final class DownloaderProcess {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[1]);
        if (args[0].equals("lock")) {
            try(var c=FileChannel.open(root.resolve("owner.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=c.lock()) {
                System.out.println("LOCKED");System.out.flush();Thread.sleep(30000);
            }return;
        }
        if (args[0].equals("crash")) {
            var state=new DownloadStateStore(root,new ObjectMapper());var m=state.active();
            state.attempt(new DownloadStateStore.Attempt(m.attemptId(),m.key(),m.discoveredAt(),null,"DOWNLOADING",null,0));
            Files.writeString(root.resolve("download-crashed.part"),"partial");Runtime.getRuntime().halt(17);
        }
        DownloadConfig cfg=(DownloadConfig)Proxy.newProxyInstance(DownloaderProcess.class.getClassLoader(),new Class[]{DownloadConfig.class},(p,m,a)->switch(m.getName()) {
            case "enabled","checkOnStartup" -> true;
            case "dataDirectory" -> root.toString();
            case "schedule" -> "off";
            case "timeZone" -> "America/New_York";
            case "connectTimeout","readTimeout" -> Duration.ofSeconds(2);
            case "operationTimeout" -> Duration.ofSeconds(10);
            case "maxBackoff","cleanupGracePeriod" -> Duration.ZERO;
            case "maxAttempts" -> 3;
            case "maxRediscoveries" -> 2;
            case "maxListingPages" -> 4;
            case "retainedGenerations" -> 2;
            case "retainedAttempts" -> 100;
            case "maxDownloadBytes","maxListingResponseBytes" -> 100000L;
            case "minFreeDiskBytes" -> 0L;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        var c=new DownloadCoordinator();c.config=cfg;c.json=new ObjectMapper();
        System.out.println("RESULT="+c.execute(new LatestArchiveDownloader(cfg,URI.create(args[0]))));
    }
}

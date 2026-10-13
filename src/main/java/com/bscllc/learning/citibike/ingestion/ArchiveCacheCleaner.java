package com.bscllc.learning.citibike.ingestion;

import com.bscllc.learning.citibike.db.DuckDbManager;
import com.bscllc.learning.citibike.download.DownloadStateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Delete superseded cache bytes only after a verified backup; audit identities remain durable. */
@ApplicationScoped
public class ArchiveCacheCleaner {
    private final DuckDbManager db;private final IngestionConfig cfg;private final ObjectMapper json;private final Clock clock;
    public ArchiveCacheCleaner(DuckDbManager db,IngestionConfig cfg,ObjectMapper json,Clock clock){this.db=db;this.cfg=cfg;this.json=json;this.clock=clock;}
    public void clean() { clean(System.nanoTime()+cfg.jobTimeout().toNanos()); }
    public void clean(long deadline) {
        Path receipt=db.dataDirectory().resolve("backup-receipt.json");
        try {
            if(!Files.isRegularFile(receipt)||Files.isSymbolicLink(receipt)||Files.size(receipt)>1048576)return;
            var document=json.readTree(receipt.toFile());Path backup=Path.of(document.path("backupDirectory").asText());
            if(!Files.isRegularFile(backup.resolve("backup-manifest.json")))return;
            var hashes=document.path("sha256");
            var manifest=json.readTree(backup.resolve("backup-manifest.json").toFile());
            if(!manifest.path("sha256").equals(hashes))return;
            boolean databaseFound=false;
            var fields=hashes.fields();
            while(fields.hasNext()) {
                if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)return;
                var field=fields.next();Path relative=Path.of(field.getKey());
                if(relative.isAbsolute()||relative.normalize().startsWith(".."))return;
                Path file=backup.resolve(relative);if(Files.isSymbolicLink(file)||!Files.isRegularFile(file)||!DownloadStateStore.digest(file).equals(field.getValue().asText()))return;
                if(field.getKey().endsWith(".duckdb"))databaseFound=true;
            }
            if(!databaseFound)return;
            db.withWriter(c->{
                List<String[]> candidates=new ArrayList<>();
                try(var s=c.prepareStatement("""
                    SELECT v.id,v.archive,v.sha256 FROM archive_versions v
                    WHERE v.downloaded_at<? AND NOT EXISTS(SELECT 1 FROM active_partitions p WHERE p.version_id=v.id)
                    AND NOT EXISTS(SELECT 1 FROM ingestion_jobs j WHERE j.version_id=v.id AND j.status IN ('QUEUED','RUNNING','WAITING'))
                    """)) {
                    s.setTimestamp(1,java.sql.Timestamp.from(clock.instant().minus(cfg.supersededRetention())));
                    try(var r=s.executeQuery()){while(r.next())candidates.add(new String[]{r.getString(1),r.getString(2),r.getString(3)});}
                }
                for(var entry:candidates) {
                  if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)break;
                  try {
                    if(!entry[1].matches("archive-[a-f0-9-]+\\.zip"))continue;
                    String relative="archives/"+entry[1];Path archived=backup.resolve(relative);Path cached=db.dataDirectory().resolve(relative);
                    if(!entry[2].equals(hashes.path(relative).asText()) || Files.isSymbolicLink(archived)||!Files.isRegularFile(archived)
                        || !DownloadStateStore.digest(archived).equals(entry[2]))continue;
                    Files.deleteIfExists(cached);
                  }catch(java.io.IOException e){throw new IngestionFailure("cache_cleanup");}
                }
                return null;
            });
        }catch(Exception ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Ingestion cache cleanup reason=backup_or_io");}
    }
}

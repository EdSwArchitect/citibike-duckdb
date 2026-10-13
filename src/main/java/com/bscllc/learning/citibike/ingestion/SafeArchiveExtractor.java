package com.bscllc.learning.citibike.ingestion;

import org.apache.commons.compress.archivers.zip.*;
import org.apache.commons.compress.utils.InputStreamStatistics;
import com.bscllc.learning.citibike.download.DownloadStateStore;
import java.nio.file.*;
import java.io.*;
import java.time.YearMonth;
import java.util.*;
import java.util.zip.CRC32;

/** ZIP names are only provenance. All extracted files get generated local names. */
public final class SafeArchiveExtractor {
    public record Csv(String entry,Path path,String sha256) {}
    public record Extracted(List<Csv> csvs,String nestedEntry,String materializationSha256,long expandedBytes) {}
    private final IngestionConfig cfg;
    private long expanded; private int entries; private long deadline;
    public SafeArchiveExtractor(IngestionConfig cfg) { this.cfg=cfg; }
    public Extracted extract(Path archive,boolean annual,YearMonth month,Path work,long jobDeadline) throws IOException {
        expanded=0;entries=0;deadline=jobDeadline;
        if(cfg.maxZipLayers()<1 || (annual && cfg.maxZipLayers()<2))throw new IngestionFailure("unsafe_archive");
        List<Csv> csvs=new ArrayList<>();String nested=null;String materialization;
        if(annual) {
            try(ZipFile zip=ZipFile.builder().setPath(archive).get()) {
                var all=validate(zip);String ym=month.toString().replace("-","");
                var months=all.stream().filter(e->!ignored(e.getName()) && !e.isDirectory()
                    && Path.of(e.getName()).getFileName().toString().matches("[0-9]{6}-citibike-tripdata(?:\\.csv)?\\.zip")).toList();
                for(var e:all) if(!ignored(e.getName()) && !e.isDirectory() && !months.contains(e))throw new IngestionFailure("unsafe_archive");
                var selected=months.stream().filter(e->Path.of(e.getName()).getFileName().toString().startsWith(ym+"-")).toList();
                if(selected.isEmpty())throw new IngestionFailure("missing_publication");
                if(selected.size()!=1)throw new IngestionFailure("ambiguous_source");
                nested=selected.getFirst().getName();Path inner=copy(zip,selected.getFirst(),work,".zip");
                materialization=DownloadStateStore.digest(inner);readCsvs(inner,work,csvs);
            }
        }else { materialization=DownloadStateStore.digest(archive);readCsvs(archive,work,csvs); }
        if(csvs.isEmpty())throw new IngestionFailure("unsafe_archive");
        return new Extracted(List.copyOf(csvs),nested,materialization,expanded);
    }
    private void readCsvs(Path archive,Path work,List<Csv> result) throws IOException {
        try(ZipFile zip=ZipFile.builder().setPath(archive).get()) {
            for(var e:validate(zip)) {
                if(ignored(e.getName()) || e.isDirectory())continue;
                if(!e.getName().endsWith(".csv"))throw new IngestionFailure("unsafe_archive");
                Path p=copy(zip,e,work,".csv");result.add(new Csv(e.getName(),p,DownloadStateStore.digest(p)));
            }
        }
    }
    private List<ZipArchiveEntry> validate(ZipFile zip) {
        var result=new ArrayList<ZipArchiveEntry>();var names=new HashSet<String>();var enumeration=zip.getEntries();
        while(enumeration.hasMoreElements()) {
            check();var e=enumeration.nextElement();if(++entries>cfg.maxEntries())throw new IngestionFailure("quota");
            String name=e.getName();
            byte[] raw=e.getRawName();if(raw!=null)for(byte value:raw)if(value==92)throw new IngestionFailure("unsafe_archive");
            if(name.isEmpty() || name.length()>1024 || name.startsWith("/") || name.contains("\\") || name.indexOf(':')>=0 || name.chars().anyMatch(Character::isISOControl))throw new IngestionFailure("unsafe_archive");
            Path path=Path.of(name);
            for(Path part:path) if(part.toString().equals("..") || part.toString().equals("."))throw new IngestionFailure("unsafe_archive");
            int mode=e.getUnixMode() & 0170000;
            if(!names.add(path.normalize().toString()) || (mode!=0 && mode!=0100000 && mode!=0040000) || e.isUnixSymlink() || e.getGeneralPurposeBit().usesEncryption()
                || (!e.isDirectory() && (e.getMethod()!=0 && e.getMethod()!=8)) || !zip.canReadEntryData(e))throw new IngestionFailure("unsafe_archive");
            if(e.getSize()>cfg.maxEntryBytes())throw new IngestionFailure("quota");result.add(e);
        }
        result.sort(Comparator.comparing(ZipArchiveEntry::getName));return result;
    }
    private Path copy(ZipFile zip,ZipArchiveEntry entry,Path work,String suffix) throws IOException {
        Path p=Files.createTempFile(work,"member-",suffix);CRC32 crc=new CRC32();long bytes=0;
        try(InputStream in=zip.getInputStream(entry);OutputStream out=Files.newOutputStream(p)) {
            if(!(in instanceof InputStreamStatistics stats))throw new IngestionFailure("unsafe_archive");
            byte[] buffer=new byte[65536];int n;
            while((n=in.read(buffer))!=-1) {
                check();bytes+=n;expanded+=n;
                if(bytes>cfg.maxEntryBytes() || expanded>cfg.maxExpandedBytes()
                    || (stats.getCompressedCount()>0 && bytes/(double)stats.getCompressedCount()>cfg.maxCompressionRatio())
                    || Files.getFileStore(work).getUsableSpace()<cfg.minFreeDiskBytes())throw new IngestionFailure("quota");
                crc.update(buffer,0,n);out.write(buffer,0,n);
            }
            if(bytes!=entry.getSize() || crc.getValue()!=entry.getCrc())throw new IngestionFailure("corrupt_archive");
        }
        return p;
    }
    private void check() { if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline)throw new IngestionFailure("deadline"); }
    private static boolean ignored(String name) {
        Path path=Path.of(name);for(Path component:path)if(component.toString().equals("__MACOSX"))return true;
        return path.getFileName().toString().equals(".DS_Store");
    }
}

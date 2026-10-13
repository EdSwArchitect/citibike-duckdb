package com.bscllc.learning.citibike.ingestion;

import org.apache.commons.compress.archivers.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class SafeArchiveExtractorTest {
    @TempDir Path root;
    IngestionConfig cfg(Map<String,String> overrides){return IngestionFixtures.settings(root,java.net.URI.create("http://localhost/tripdata"),overrides).getConfigMapping(IngestionConfig.class);}
    SafeArchiveExtractor.Extracted extract(byte[] bytes,boolean annual,Map<String,String> overrides)throws Exception {
        Path archive=Files.createTempFile(root,"input-",".zip");Files.write(archive,bytes);Path work=Files.createTempDirectory(root,"work-");
        return new SafeArchiveExtractor(cfg(overrides)).extract(archive,annual,YearMonth.of(2023,7),work,System.nanoTime()+Duration.ofSeconds(10).toNanos());
    }
    @Test void allCsvMembersAndKnownMetadataAreHandledDeterministically()throws Exception {
        var result=extract(IngestionFixtures.zipStrings(Map.of("folder/b.csv","b","folder/a.csv","a","__MACOSX/._a.csv","meta","folder/.DS_Store","meta","folder/__MACOSX/._nested.csv","meta")),false,Map.of());
        assertEquals(List.of("folder/a.csv","folder/b.csv"),result.csvs().stream().map(SafeArchiveExtractor.Csv::entry).toList());
        assertEquals(2,result.expandedBytes());
    }
    @Test void unsafeNamesAndUnsupportedLayoutsAreRejected()throws Exception {
        for(String name:List.of("../escape.csv","/absolute.csv","C:/drive.csv","bad\\name.csv","nested.zip","unknown.txt","a/../alias.csv")) {
            byte[] bytes=IngestionFixtures.zipStrings(Map.of(name,"payload"));assertThrows(IngestionFailure.class,()->extract(bytes,false,Map.of()),name);
        }
        assertFalse(Files.exists(root.getParent().resolve("escape.csv")));
        var dup=IngestionFixtures.zipStrings(Map.of("a//b.csv","one","a/b.csv","two"));assertThrows(IngestionFailure.class,()->extract(dup,false,Map.of()));
    }
    @Test void symlinksEncryptionUnsupportedMethodsAndZip64()throws Exception {
        var bytes=new ByteArrayOutputStream();try(var out=new ZipArchiveOutputStream(bytes)) {
            var e=new ZipArchiveEntry("link.csv");e.setUnixMode(0120777);out.putArchiveEntry(e);out.write("../outside".getBytes());out.closeArchiveEntry();
        }
        assertThrows(IngestionFailure.class,()->extract(bytes.toByteArray(),false,Map.of()));
        byte[] encrypted=IngestionFixtures.zipStrings(Map.of("a.csv","a"));encrypted[6]|=1;
        for(int i=0;i<encrypted.length-4;i++)if(encrypted[i]==0x50&&encrypted[i+1]==0x4b&&encrypted[i+2]==1&&encrypted[i+3]==2)encrypted[i+8]|=1;
        assertThrows(IngestionFailure.class,()->extract(encrypted,false,Map.of()));
        var zip64=new ByteArrayOutputStream();try(var out=new ZipArchiveOutputStream(zip64)) {
            out.setUseZip64(Zip64Mode.Always);var e=new ZipArchiveEntry("large-format.csv");out.putArchiveEntry(e);out.write("small bytes".getBytes());out.closeArchiveEntry();
        }
        assertEquals(1,extract(zip64.toByteArray(),false,Map.of()).csvs().size());
    }
    @Test void entryExpandedRatioCountAndNestingBudgetsApply()throws Exception {
        byte[] bytes=IngestionFixtures.zipStrings(Map.of("a.csv","a".repeat(10000),"b.csv","b"));
        assertThrows(IngestionFailure.class,()->extract(bytes,false,Map.of("citibike.ingestion.max-entry-bytes","100")));
        assertThrows(IngestionFailure.class,()->extract(bytes,false,Map.of("citibike.ingestion.max-expanded-bytes","100")));
        assertThrows(IngestionFailure.class,()->extract(bytes,false,Map.of("citibike.ingestion.max-entries","1")));
        assertThrows(IngestionFailure.class,()->extract(bytes,false,Map.of("citibike.ingestion.max-compression-ratio","2")));
        byte[] nested=IngestionFixtures.zip(Map.of("2023/202307-citibike-tripdata.zip",IngestionFixtures.zipStrings(Map.of("trips.csv","a"))));
        assertThrows(IngestionFailure.class,()->extract(nested,true,Map.of("citibike.ingestion.max-zip-layers","1")));
        byte[] deeper=IngestionFixtures.zip(Map.of("202307-citibike-tripdata.zip",IngestionFixtures.zipStrings(Map.of("another.zip","data"))));
        assertThrows(IngestionFailure.class,()->extract(deeper,true,Map.of()));
    }
    @Test void wrongMonthlyEnvelopeCorruptZipAndJobDeadlineFail()throws Exception {
        byte[] missing=IngestionFixtures.zip(Map.of("202306-citibike-tripdata.zip",IngestionFixtures.zipStrings(Map.of("trips.csv","a"))));
        assertEquals("missing_publication",assertThrows(IngestionFailure.class,()->extract(missing,true,Map.of())).code());
        assertThrows(Exception.class,()->extract(new byte[]{1,2,3},false,Map.of()));
        Path archive=root.resolve("deadline.zip");Files.write(archive,IngestionFixtures.zipStrings(Map.of("a.csv","a")));
        assertThrows(IngestionFailure.class,()->new SafeArchiveExtractor(cfg(Map.of())).extract(archive,false,YearMonth.of(2023,7),root,System.nanoTime()-1));
    }
    @Test void storedEntryCrcMismatchIsRejected()throws Exception {
        byte[] payload="UNIQUE-CRC-PAYLOAD".getBytes();var crc=new java.util.zip.CRC32();crc.update(payload);
        var bytes=new ByteArrayOutputStream();try(var zip=new java.util.zip.ZipOutputStream(bytes)) {
            var entry=new java.util.zip.ZipEntry("crc.csv");entry.setMethod(java.util.zip.ZipEntry.STORED);entry.setSize(payload.length);entry.setCrc(crc.getValue());zip.putNextEntry(entry);zip.write(payload);zip.closeEntry();
        }
        byte[] corrupt=bytes.toByteArray();for(int i=0;i<corrupt.length-payload.length;i++)if(corrupt[i]=='U'&&corrupt[i+1]=='N'){corrupt[i]^=1;break;}
        assertEquals("corrupt_archive",assertThrows(IngestionFailure.class,()->extract(corrupt,false,Map.of())).code());
    }

    @Test void unsupportedMethodsAndFalseSizeDeclarationsReject()throws Exception {
        byte[] unsupported=IngestionFixtures.zipStrings(Map.of("a.csv","data"));unsupported[8]=99;unsupported[9]=0;
        for(int i=0;i<unsupported.length-30;i++)if(unsupported[i]==0x50&&unsupported[i+1]==0x4b&&unsupported[i+2]==1&&unsupported[i+3]==2){unsupported[i+10]=99;unsupported[i+11]=0;}
        assertThrows(IngestionFailure.class,()->extract(unsupported,false,Map.of()));
        byte[] wrongSize=IngestionFixtures.zipStrings(Map.of("a.csv","nontrivial-payload"));
        for(int i=0;i<wrongSize.length-30;i++)if(wrongSize[i]==0x50&&wrongSize[i+1]==0x4b&&wrongSize[i+2]==1&&wrongSize[i+3]==2){wrongSize[i+24]=1;wrongSize[i+25]=0;wrongSize[i+26]=0;wrongSize[i+27]=0;}
        assertThrows(Exception.class,()->extract(wrongSize,false,Map.of()));
    }

}

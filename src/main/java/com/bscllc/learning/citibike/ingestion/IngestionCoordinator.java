package com.bscllc.learning.citibike.ingestion;

import com.bscllc.learning.citibike.db.*;
import com.bscllc.learning.citibike.download.*;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.bscllc.learning.citibike.validation.InvalidRequestException;

@ApplicationScoped
public class IngestionCoordinator {
    private static final Logger LOG=LoggerFactory.getLogger(IngestionCoordinator.class);
    private final DuckDbManager db;private final IngestionConfig cfg;private final IngestionRepository jobs;
    private final MonthlyImporter importer;private final IngestionMetrics metrics;private final Clock clock;
    @jakarta.inject.Inject ArchiveCacheCleaner cacheCleaner;
    private final AtomicBoolean processing=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"citibike-ingestion");t.setDaemon(true);return t;});
    public IngestionCoordinator(DuckDbManager db,IngestionConfig cfg,IngestionRepository jobs,MonthlyImporter importer,IngestionMetrics metrics,Clock clock){
        this.db=db;this.cfg=cfg;this.jobs=jobs;this.importer=importer;this.metrics=metrics;this.clock=clock;
    }
    public boolean enabled(){return cfg.enabled()&&db.fileMode();}
    public YearMonth previousMonth(){return YearMonth.now(clock.withZone(ZoneId.of(cfg.timeZone()))).minusMonths(1);}
    public YearMonth month(String value){
        try{if(value==null || !value.matches("[0-9]{4}-[0-9]{2}"))throw new IllegalArgumentException();
            YearMonth m=YearMonth.parse(value);if(m.getYear()<1 || m.isAfter(previousMonth()))throw new IllegalArgumentException();return m;
        }catch(RuntimeException e){throw new InvalidRequestException("Month must be YYYY-MM and a complete past calendar month");}
    }
    public ArchiveCatalog.City city(String value){
        try{var city=value==null?ArchiveCatalog.City.NYC:ArchiveCatalog.City.valueOf(value);
            if(city==ArchiveCatalog.City.JC && !cfg.includeJerseyCity())throw new IllegalArgumentException();return city;
        }catch(RuntimeException e){throw new InvalidRequestException("City must be NYC, or explicitly enabled JC");}
    }
    public List<IngestionRepository.Job> enqueue(String city,String from,String to,String trigger){
        if(!enabled())throw new InvalidRequestException("Ingestion requires enabled file storage");
        YearMonth first=month(from),last=month(to);long count=ChronoUnit.MONTHS.between(first,last)+1;
        if(count<1 || count>cfg.maxBackfillMonths())throw new InvalidRequestException("Backfill range exceeds configured bounds");
        List<YearMonth> months=new ArrayList<>();for(YearMonth m=first;!m.isAfter(last);m=m.plusMonths(1))months.add(m);
        var result=jobs.enqueue(city(city),months,trigger,true);refreshMetrics();kick();return result;
    }
    public void monthly(){
        if(!enabled())return;
        for(var city:cities())jobs.enqueue(city,List.of(previousMonth()),"monthly",false);refreshMetrics();kick();
    }
    public void catchUp(){
        if(!enabled())return;
        YearMonth end=previousMonth(),start=end.minusMonths(cfg.lookbackMonths()-1L);
        if(cfg.catchUpStartMonth().isPresent()){YearMonth configured=month(cfg.catchUpStartMonth().get());if(configured.isAfter(start))start=configured;}
        int added=0;
        // Round-robin over the recent window: committed earlier months are
        // rechecked, but never starve newer missing months with a small run cap.
        List<YearMonth> range=new ArrayList<>();for(YearMonth m=start;!m.isAfter(end);m=m.plusMonths(1))range.add(m);
        if(range.isEmpty())return;
        record Request(ArchiveCatalog.City city,YearMonth month) {}
        List<Request> requests=new ArrayList<>();for(YearMonth m:range)for(var city:cities())requests.add(new Request(city,m));
        int rotation=(int)Math.floorMod(LocalDate.now(clock.withZone(ZoneId.of(cfg.timeZone()))).toEpochDay(),requests.size());
        for(int i=0;i<requests.size() && added<cfg.jobsPerRun();i++) {
            var request=requests.get((i+rotation)%requests.size());
            var previous=jobs.latest(request.city(),request.month());if(previous!=null && previous.status().equals("OVERDUE"))continue;
            jobs.enqueue(request.city(),List.of(request.month()),"catch-up",false);added++;
        }
        refreshMetrics();kick();
    }
    private List<ArchiveCatalog.City> cities(){return cfg.includeJerseyCity()?List.of(ArchiveCatalog.City.NYC,ArchiveCatalog.City.JC):List.of(ArchiveCatalog.City.NYC);}
    void start(@Observes @Priority(3000) StartupEvent event){
        if(!enabled())return;validate();jobs.recover();
        Path root=db.dataDirectory();
        for(String sub:List.of("archives","jobs"))try{Files.createDirectories(root.resolve(sub));}catch(java.io.IOException e){throw new IllegalStateException("Cannot create ingestion directories");}
        try(var files=Files.list(root.resolve("jobs"))){files.forEach(MonthlyImporter::deleteDirectory);}catch(java.io.IOException e){throw new IllegalStateException("Cannot recover ingestion workspaces");}
        try(var files=Files.list(root.resolve("archives"))){for(var file:files.toList())if(file.toString().endsWith(".part"))Files.deleteIfExists(file);}catch(java.io.IOException e){throw new IllegalStateException("Cannot recover partial archives");}
        refreshMetrics();
        if(cfg.backfill().from().isPresent()!=cfg.backfill().to().isPresent())throw new IllegalStateException("Both configured backfill bounds are required");
        if(cfg.backfill().from().isPresent()) {
            YearMonth first=month(cfg.backfill().from().get()),last=month(cfg.backfill().to().get());
            long count=ChronoUnit.MONTHS.between(first,last)+1;if(count<1||count>cfg.maxBackfillMonths())throw new IllegalStateException("Configured backfill exceeds bounds");
            List<YearMonth> months=new ArrayList<>();for(YearMonth m=first;!m.isAfter(last);m=m.plusMonths(1))months.add(m);
            for(var city:cities())jobs.enqueue(city,months,"backfill",true);
        }
        if(cfg.checkOnStartup())catchUp();else kick();
    }
    public void kick(){if(worker.isShutdown()||!processing.compareAndSet(false,true))return;try{worker.submit(()->{
        boolean drainFailed=false;
        try{while(!Thread.currentThread().isInterrupted()){var job=jobs.takeNext();if(job==null)break;run(job);}}
        catch(RuntimeException e){drainFailed=true;LOG.warn("Ingestion worker reason=database");}
        finally{processing.set(false);metrics.running(false);try{refreshMetrics();if(!drainFailed && !worker.isShutdown() && jobs.hasEligible())kick();}catch(RuntimeException ignored){}}
    });}catch(RejectedExecutionException shutdown){processing.set(false);}}
    /** Synchronous entry used by deterministic fixture/profile tests; all real triggers use the durable queue. */
    public void run(IngestionRepository.Job job){
        long deadline=System.nanoTime()+cfg.jobTimeout().toNanos();Path workspace=null;var timer=metrics.start();String outcome="failed";
        metrics.running(true);String versionId=null;String stage="discovery";
        try {
            workspace=Files.createTempDirectory(db.dataDirectory().resolve("jobs"),"job-");
            LatestArchiveDownloader http=LatestArchiveDownloader.forIngestion(new IngestionHttpLimits(cfg),cfg.sourceUri());http.begin();
            IngestionRepository.Version version=null;ArchiveCatalog.Selection selected=null;
            for(int cycle=0;cycle<=cfg.maxRediscoveries();cycle++)try {
                selected=new ArchiveCatalog(http,cfg).discover(ArchiveCatalog.City.valueOf(job.city()),YearMonth.parse(job.month()));
                stage="download";var source=http.head(selected.source());version=jobs.cached(source);Path cache=db.dataDirectory().resolve("archives");
                if(version==null || !validCached(version,cache)) {
                    long used;try(var files=Files.list(cache)){used=files.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(java.io.IOException e){throw new IngestionFailure("quota");}}).sum();}
                    if(source.size()>cfg.cacheQuotaBytes()-used || Files.getFileStore(cache).getUsableSpace()-source.size()-cfg.maxExpandedBytes()<cfg.minFreeDiskBytes())throw new IngestionFailure("quota");
                    var downloaded=http.download(source,cache);String name="archive-"+UUID.randomUUID()+".zip";
                    if(version!=null && downloaded.checksum().equals(version.sha256())) {
                        name=version.archive();Files.move(downloaded.path(),cache.resolve(name),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                    }else {
                        Files.move(downloaded.path(),cache.resolve(name),StandardCopyOption.ATOMIC_MOVE);version=null;
                    }
                    try(var directory=FileChannel.open(cache,StandardOpenOption.READ)){directory.force(true);}
                    if(version==null)version=jobs.saveVersion(source,downloaded.checksum(),name);metrics.bytes(downloaded.size(),0);
                }
                break;
            }catch(LatestArchiveDownloader.Changed race){if(cycle==cfg.maxRediscoveries())throw race;}
            if(version==null || selected==null)throw new IngestionFailure("download");
            final String candidateVersion=version.id();db.withWriter(c->{try(var s=c.prepareStatement("UPDATE ingestion_jobs SET version_id=? WHERE id=?")){s.setString(1,candidateVersion);s.setString(2,job.id());s.executeUpdate();}return null;});
            versionId=version.id();
            var active=jobs.active(job);
            if(active!=null && active.version().equals(version.id()) && active.policy().equals(MonthlyImporter.POLICY)) {
                jobs.finish(job,"UNCHANGED",null,version.id(),active.rows(),null);outcome="unchanged";return;
            }
            stage="extract";var extraction=new SafeArchiveExtractor(cfg).extract(db.dataDirectory().resolve("archives").resolve(version.archive()),selected.annual(),YearMonth.parse(job.month()),workspace,deadline);
            metrics.bytes(0,extraction.expandedBytes());stage="import";boolean committed=importer.importMonth(job,version,extraction,deadline);
            outcome=committed?"committed":"unchanged";if(committed)metrics.success(clock.instant());
        }catch(Exception failure){
            if(jobs.committed(job)){outcome="committed";metrics.success(clock.instant());}
            else {
                String reason=failure instanceof IngestionFailure f?f.code():failure instanceof java.io.IOException?(stage.equals("extract")?"corrupt_archive":"download"):"database";
                Instant retry=null;String state="FAILED";
                if(reason.equals("missing_publication")) {
                    LocalDate end=YearMonth.parse(job.month()).atEndOfMonth();
                    boolean overdue=LocalDate.now(clock.withZone(ZoneId.of(cfg.timeZone()))).isAfter(end.plusDays(cfg.publicationWaitDays()));
                    state=overdue?"OVERDUE":"WAITING";reason=overdue?"publication_overdue":"missing_publication";
                    if(!overdue)retry=clock.instant().plus(Duration.ofDays(1));
                }
                jobs.finish(job,state,reason,versionId,0,retry);outcome=state.toLowerCase(Locale.ROOT);
                LOG.warn("Ingestion job={} stage={} reason={}",job.id(),stage,reason);
            }
        }finally{
            if(workspace!=null)MonthlyImporter.deleteDirectory(workspace);if(cacheCleaner!=null)cacheCleaner.clean(deadline);metrics.running(false);metrics.finished(timer,outcome);refreshMetrics();
        }
    }
    private boolean validCached(IngestionRepository.Version v,Path cache)throws java.io.IOException {
        if(!v.archive().matches("archive-[a-f0-9-]+\\.zip"))throw new IngestionFailure("unsafe_archive");
        Path file=cache.resolve(v.archive());return !Files.isSymbolicLink(file)&&Files.isRegularFile(file)&&Files.size(file)==v.bytes()&&DownloadStateStore.digest(file).equals(v.sha256());
    }
    private void refreshMetrics(){var status=jobs.status();metrics.pending(status.pending());status.coverage().stream().map(IngestionRepository.Coverage::committedAt).max(Comparator.naturalOrder()).ifPresent(metrics::success);}
    private void validate(){
        if(cfg.lookbackMonths()<1||cfg.lookbackMonths()>24||cfg.jobsPerRun()<1||cfg.jobsPerRun()>100||cfg.maxQueuedJobs()<1||cfg.maxQueuedJobs()>1000
           ||cfg.maxBackfillMonths()<1||cfg.maxBackfillMonths()>240||cfg.maxAttempts()<1||cfg.maxAttempts()>20||cfg.maxRediscoveries()<0||cfg.maxRediscoveries()>20
           ||cfg.publicationWaitDays()<1||cfg.maxEntryBytes()<1||cfg.maxExpandedBytes()<1||cfg.maxDownloadBytes()<1||cfg.maxEntries()<1||cfg.maxZipLayers()<1||cfg.maxZipLayers()>2
           ||!Double.isFinite(cfg.maxCompressionRatio())||cfg.maxCompressionRatio()<1||cfg.minFreeDiskBytes()<0||cfg.cacheQuotaBytes()<1||cfg.maxListingPages()<1||cfg.maxListingResponseBytes()<1
           ||cfg.supersededRetention().isNegative()||cfg.jobTimeout().isNegative()||cfg.jobTimeout().isZero()||cfg.sqlTimeout().toSeconds()<1||cfg.connectTimeout().isNegative()||cfg.connectTimeout().isZero()
           ||cfg.readTimeout().isNegative()||cfg.readTimeout().isZero()||cfg.downloadTimeout().isNegative()||cfg.downloadTimeout().isZero()||cfg.maxBackoff().isNegative())throw new IllegalStateException("Invalid ingestion limits");
        ZoneId.of(cfg.timeZone());
    }
    void stop(@Observes @Priority(1000) ShutdownEvent event){worker.shutdownNow();db.cancelWriter();try{worker.awaitTermination(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}

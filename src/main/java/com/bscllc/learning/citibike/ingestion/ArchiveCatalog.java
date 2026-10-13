package com.bscllc.learning.citibike.ingestion;

import com.bscllc.learning.citibike.download.LatestArchiveDownloader;
import java.io.IOException;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Pattern;

public final class ArchiveCatalog {
    public enum City { NYC, JC }
    public record Selection(LatestArchiveDownloader.Source source, boolean annual) {}
    private final LatestArchiveDownloader http;
    private final IngestionConfig config;
    public ArchiveCatalog(LatestArchiveDownloader http,IngestionConfig config) { this.http=http;this.config=config; }
    public Selection discover(City city,YearMonth month) throws IOException {
        if(city==City.JC && !config.includeJerseyCity())throw new IngestionFailure("city_disabled");
        String ym=month.toString().replace("-","");String year=Integer.toString(month.getYear());
        var objects=http.listObjects((city==City.JC?"JC-":"")+year);
        if(objects.size()>config.maxDiscoveredObjects())throw new IngestionFailure("quota");
        String prefix=city==City.JC?"JC-":"";
        Pattern monthly=Pattern.compile(Pattern.quote(prefix+ym)+"-citibike-tripdata(?:\\.csv)?\\.zip");
        var matches=objects.stream().filter(s->monthly.matcher(s.key()).matches()).toList();
        if(matches.size()>1)throw new IngestionFailure("ambiguous_source");
        if(matches.size()==1)return new Selection(matches.getFirst(),false);
        if(city==City.NYC) {
            var annual=objects.stream().filter(s->s.key().equals(year+"-citibike-tripdata.zip")).toList();
            if(annual.size()==1)return new Selection(annual.getFirst(),true);
        }
        throw new IngestionFailure("missing_publication");
    }
}

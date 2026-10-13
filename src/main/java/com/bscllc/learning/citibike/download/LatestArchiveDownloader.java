package com.bscllc.learning.citibike.download;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Bounded HTTP and XML handling; production endpoint cannot be overridden by configuration. */
public final class LatestArchiveDownloader {
    public record Source(String key, String month, String etag, String version, String modified, long size) {}
    public record Payload(Path path, String checksum, long size) {}
    static final Pattern KEY = Pattern.compile("[0-9]{6}-citibike-tripdata\\.zip");
    private final DownloadConfig cfg;
    private final URI origin;
    private long deadline;
    private int retries;
    public LatestArchiveDownloader(DownloadConfig cfg) { this(cfg, URI.create("https://s3.amazonaws.com/tripdata")); }
    // Package-private origin injection exists exclusively for local stub tests.
    LatestArchiveDownloader(DownloadConfig cfg, URI origin) { this.cfg = cfg; this.origin = origin; }
    public void begin() { deadline = System.nanoTime() + cfg.operationTimeout().toNanos(); retries = 0; }
    public int retries() { return retries; }
    private void check() throws IOException { if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new IOException("DEADLINE"); }
    private int timeout(Duration limit) throws IOException { check(); return (int)Math.max(1, Math.min(Integer.MAX_VALUE,
            Math.min(limit.toMillis(), (deadline - System.nanoTime()) / 1_000_000))); }
    private HttpURLConnection open(String suffix, String method, Source source) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(origin + suffix).toURL().openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(timeout(cfg.connectTimeout()));
        c.setReadTimeout(timeout(cfg.readTimeout())); c.setRequestMethod(method);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (source!=null) c.setRequestProperty("x-amz-checksum-mode", "ENABLED");
        if (source != null && source.version() == null && source.etag() != null) c.setRequestProperty("If-Match", source.etag());
        return c;
    }
    @FunctionalInterface interface Request<T> { T run() throws IOException; }
    private <T> T retry(Request<T> request) throws IOException {
        IOException last = null;
        for (int a = 0; a < cfg.maxAttempts(); a++) {
            check();
            try { return request.run(); } catch (Permanent e) { throw e; }
            catch (IOException e) {
                last = e; if (a + 1 == cfg.maxAttempts()) break; retries++;
                long backoff = Math.min(cfg.maxBackoff().toMillis(), 100L * (1L << Math.min(a, 20)));
                if (e instanceof Transient t) backoff = Math.min(cfg.maxBackoff().toMillis(), Math.max(backoff, t.after));
                long delay = Math.min(backoff + (backoff == 0 ? 0 : new Random().nextLong(Math.max(1,backoff / 4))), cfg.maxBackoff().toMillis());
                if (delay >= (deadline - System.nanoTime()) / 1_000_000) throw new IOException("DEADLINE");
                try { Thread.sleep(delay); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("INTERRUPTED"); }
            }
        }
        throw last;
    }
    static class Permanent extends IOException { Permanent(String code) { super(code); } }
    public static class Changed extends Permanent { Changed() { super("SOURCE_CHANGED"); } }
    static class Transient extends IOException { final long after; Transient(long after) { super("HTTP_TRANSIENT"); this.after = after; } }
    private void response(HttpURLConnection c, boolean changed) throws IOException {
        int code = c.getResponseCode();
        if (changed && (code == 404 || code == 412)) throw new Changed();
        if (code == 408 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504) {
            long delay = 0; String after = c.getHeaderField("Retry-After");
            if (after != null) try { delay = Math.multiplyExact(Long.parseLong(after), 1000); }
            catch (RuntimeException e) { try { delay = Math.max(0, Duration.between(Instant.now(), ZonedDateTime.parse(after, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()); } catch (RuntimeException ignored) {} }
            throw new Transient(Math.max(0,delay));
        }
        if (code != 200) throw new Permanent("HTTP_REJECTED");
    }
    private byte[] listing(String suffix) throws IOException {
        return retry(() -> {
            HttpURLConnection c = open(suffix,"GET",null);
            try { response(c,false); long length = c.getContentLengthLong();
                if (length > cfg.maxListingResponseBytes()) throw new Permanent("LISTING_LIMIT");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) { byte[] b = new byte[8192]; int n;
                    while (true) { c.setReadTimeout(timeout(cfg.readTimeout())); n=in.read(b); if(n==-1)break; check(); if (out.size() + (long)n > cfg.maxListingResponseBytes()) throw new Permanent("LISTING_LIMIT"); out.write(b,0,n); } }
                if (length >= 0 && out.size() != length) throw new IOException("TRUNCATED"); return out.toByteArray();
            } finally { c.disconnect(); }
        });
    }
    static String text(Element e, String name) { var n = e.getElementsByTagNameNS("*",name); return n.getLength() == 0 ? null : n.item(0).getTextContent(); }
    static String encode(String v) { return URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8); }
    public java.util.List<Source> listObjects(String prefix) throws IOException {
         String token = null; Set<String> tokens = new HashSet<>(); Map<String,Source> seen = new LinkedHashMap<>();
        for (int page = 0; page < cfg.maxListingPages(); page++) {
            byte[] xml = listing("?list-type=2&max-keys=1000&encoding-type=url" + (prefix==null?"":"&prefix="+encode(prefix)) + (token == null ? "" : "&continuation-token=" + encode(token)));
            Element root;
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                f.setFeature("http://xml.org/sax/features/external-general-entities",false);
                f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
                f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
                var builder = f.newDocumentBuilder();
                builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() { @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; } });
                root = builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
                if (!"ListBucketResult".equals(root.getLocalName())) throw new IllegalArgumentException();
                var objects = root.getElementsByTagNameNS("*","Contents");
                for (int i=0;i<objects.getLength();i++) {
                    Element o = (Element) objects.item(i); String key = text(o,"Key");
                    if (key == null || key.length()>1024) throw new IllegalArgumentException();
                    if ("url".equals(text(root,"EncodingType"))) key = URLDecoder.decode(key.replace("+","%2B"),java.nio.charset.StandardCharsets.UTF_8);
                    long size = Long.parseLong(text(o,"Size")); if (size < 0) throw new IllegalArgumentException();
                    Source s = new Source(key,null,text(o,"ETag"),null,text(o,"LastModified"),size);
                    Source previous = seen.putIfAbsent(key,s); if (previous != null && !previous.equals(s)) throw new Permanent("LISTING_CONFLICT");
                    if(seen.size()>100000)throw new Permanent("LISTING_LIMIT");
                }
            } catch (Permanent e) { throw e; } catch (Exception e) { throw new Permanent("INVALID_LISTING"); }
            String truncated = text(root,"IsTruncated");
            if ("false".equals(truncated)) return java.util.List.copyOf(seen.values());
            if (!"true".equals(truncated)) throw new Permanent("INVALID_LISTING");
            token = text(root,"NextContinuationToken");
            if (token == null || token.isBlank() || !tokens.add(token)) throw new Permanent("INVALID_PAGINATION");
        }
        throw new Permanent("LISTING_LIMIT");
    }
    public Source discover() throws IOException {
        Source best=null;
        for(Source source:listObjects(null)) {
            String key=source.key();if(!KEY.matcher(key).matches())continue;
            int year=Integer.parseInt(key.substring(0,4)),month=Integer.parseInt(key.substring(4,6));
            if(year==0 || month<1 || month>12)continue;
            Source candidate=new Source(key,key.substring(0,6),source.etag(),source.version(),source.modified(),source.size());
            if(best==null || candidate.month().compareTo(best.month())>0)best=candidate;
        }
        return best;
    }
    /** Trusted deployment origin for monthly ingestion; manual requests cannot change it. */
    public static LatestArchiveDownloader forIngestion(DownloadConfig limits, URI origin) {
        if(origin.getUserInfo()!=null || origin.getQuery()!=null || origin.getFragment()!=null
            || !(origin.getScheme().equals("https") || (origin.getScheme().equals("http") && java.util.Set.of("localhost","127.0.0.1","[::1]").contains(origin.getHost()))))
            throw new IllegalArgumentException("Ingestion origin must be HTTPS or a local test stub");
        return new LatestArchiveDownloader(limits,origin);
    }
    public Source head(Source s) throws IOException {
        return retry(() -> { HttpURLConnection c = open("/"+s.key(),"HEAD",null);
            try { response(c,true); long size = c.getContentLengthLong(); if (size < 0) throw new Permanent("MISSING_LENGTH");
                String version = c.getHeaderField("x-amz-version-id"); if ("null".equals(version)) version=null;
                return new Source(s.key(),s.month(),c.getHeaderField("ETag"),version,c.getHeaderField("Last-Modified"),size);
            } finally { c.disconnect(); } });
    }
    public Payload download(Source s, Path root) throws IOException {
        if (s.size() > cfg.maxDownloadBytes()) throw new Permanent("SIZE_LIMIT");
        if (Files.getFileStore(root).getUsableSpace() - s.size() < cfg.minFreeDiskBytes()) throw new Permanent("DISK_SPACE");
        return retry(() -> {
            Path part = Files.createTempFile(root,"download-",".part"); boolean success=false;
            HttpURLConnection c = open("/" + s.key() + (s.version()==null ? "" : "?versionId="+encode(s.version())),"GET",s);
            try { response(c,true);
                if (c.getContentLengthLong() >= 0 && c.getContentLengthLong() != s.size()) throw new Changed();
                if (s.etag()!=null && !s.etag().equals(c.getHeaderField("ETag"))) throw new Changed();
                if (s.version()!=null && !s.version().equals(c.getHeaderField("x-amz-version-id"))) throw new Changed();
                long bytes=0; var digest=DownloadStateStore.sha256();
                try (InputStream in=c.getInputStream(); OutputStream out=Files.newOutputStream(part)) {
                    byte[] b=new byte[65536]; int n;
                    while (true) { c.setReadTimeout(timeout(cfg.readTimeout())); n=in.read(b); if(n==-1)break; check(); bytes+=n; if (bytes>cfg.maxDownloadBytes() || bytes>s.size()) throw new Permanent("SIZE_LIMIT"); out.write(b,0,n); digest.update(b,0,n); }
                }
                if (bytes!=s.size()) throw new IOException("TRUNCATED");
                byte[] hash=digest.digest(); String expected=c.getHeaderField("x-amz-checksum-sha256");
                if (expected!=null && "FULL_OBJECT".equals(c.getHeaderField("x-amz-checksum-type")) && !Base64.getEncoder().encodeToString(hash).equals(expected)) throw new Permanent("CHECKSUM_MISMATCH");
                try (var channel=FileChannel.open(part,StandardOpenOption.WRITE)) { channel.force(true); }
                success=true; return new Payload(part,HexFormat.of().formatHex(hash),bytes);
            } finally { c.disconnect(); if (!success) Files.deleteIfExists(part); }
        });
    }
}

package com.bscllc.learning.citibike.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import static java.nio.file.StandardOpenOption.*;

/** All publication files are immutable; active.json is the sole commit pointer. */
public final class DownloadStateStore {
    public record Manifest(String attemptId, String key, String month, String etag, String version,
                           String lastModified, String checksum, long size, String archive,
                           String discoveredAt, String downloadedAt, String completedAt, String status) {}
    public record Attempt(String id, String key, String startedAt, String completedAt,
                          String status, String reason, int retries) {}
    private final Path root;
    private final ObjectMapper json;
    public DownloadStateStore(Path root, ObjectMapper json) throws IOException {
        this.root = root.toAbsolutePath().normalize(); this.json = json;
        Files.createDirectories(this.root);
        if (Files.isSymbolicLink(this.root)) throw new IOException("UNSAFE_DIRECTORY");
    }
    public Path root() { return root; }
    public Manifest active() throws IOException {
        Path p = root.resolve("active.json");
        if (Files.isSymbolicLink(p) || (Files.exists(p) && Files.size(p) > 65536)) throw new IOException("INVALID_STATE");
        return Files.exists(p) ? json.readValue(p.toFile(), Manifest.class) : null;
    }
    public boolean valid(Manifest m) throws IOException {
        if (m == null || m.archive() == null || !m.archive().matches("archive-[a-f0-9-]+\\.zip")
                || m.key()==null || !LatestArchiveDownloader.KEY.matcher(m.key()).matches()
                || !m.key().substring(0,6).equals(m.month()) || m.size()<0
                || m.checksum()==null || !m.checksum().matches("[a-f0-9]{64}")
                || m.attemptId()==null || !m.attemptId().matches("[a-f0-9-]+")
                || !"SUCCEEDED".equals(m.status())) return false;
        Path p = root.resolve(m.archive());
        return !Files.isSymbolicLink(p) && Files.isRegularFile(p) && Files.size(p) == m.size()
                && digest(p).equals(m.checksum());
    }
    public void publish(Manifest m) throws IOException { atomicJson(root.resolve("active.json"), m); }
    public void attempt(Attempt a) throws IOException { atomicJson(root.resolve("attempt-" + a.id() + ".json"), a); }
    public void atomicJson(Path target, Object value) throws IOException {
        Path temp = Files.createTempFile(root, "state-", ".part");
        try {
            try (FileChannel c = FileChannel.open(temp, WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(json.writeValueAsBytes(value));
                while (bytes.hasRemaining()) c.write(bytes);
                c.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory();
        } finally { Files.deleteIfExists(temp); }
    }
    public void syncDirectory() throws IOException {
        try (FileChannel c = FileChannel.open(root, READ)) { c.force(true); }
    }
    public void recover() throws IOException {
        Manifest committed;
        try { committed = active(); } catch (IOException e) { committed = null; }
        try (var files = Files.list(root)) {
            for (Path p : files.toList()) {
                if (p.getFileName().toString().endsWith(".part")) Files.deleteIfExists(p);
                if (p.getFileName().toString().matches("attempt-[a-f0-9-]+\\.json")) {
                    Attempt a;
                    try {
                        if (Files.isSymbolicLink(p) || Files.size(p)>65536) throw new IOException("INVALID_STATE");
                        a = json.readValue(p.toFile(), Attempt.class);
                        if (a.id()==null || !a.id().matches("[a-f0-9-]+") || a.status()==null) throw new IOException("INVALID_STATE");
                    } catch (IOException invalid) {
                        Files.move(p, root.resolve(p.getFileName()+".invalid"), StandardCopyOption.REPLACE_EXISTING);
                        continue;
                    }
                    if (a.status().equals("DISCOVERING") || a.status().equals("DOWNLOADING")) {
                        boolean success = committed != null && valid(committed) && committed.attemptId().equals(a.id());
                        attempt(new Attempt(a.id(), a.key(), a.startedAt(), java.time.Instant.now().toString(),
                                success ? "SUCCEEDED" : "INTERRUPTED", "RECOVERED", a.retries()));
                    }
                }
            }
        }
    }
    public void cleanup(DownloadConfig cfg) throws IOException {
        Manifest m;
        try { m = active(); } catch (IOException e) { return; }
        try (var files = Files.list(root)) {
            var sorted = files.filter(p -> p.getFileName().toString().matches("(archive-[a-f0-9-]+\\.zip|attempt-[a-f0-9-]+\\.json(?:\\.invalid)?)"))
                    .sorted((a,b) -> { try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); }
                        catch (IOException e) { return 0; } }).toList();
            int archives = 0, attempts = 0;
            for (Path p : sorted) {
                boolean archive = p.getFileName().toString().startsWith("archive-");
                boolean excess = archive ? ++archives > cfg.retainedGenerations() : ++attempts > cfg.retainedAttempts();
                boolean active = m != null && p.getFileName().toString().equals(m.archive());
                if (excess && !active && (!archive || Files.getLastModifiedTime(p).toInstant().isBefore(java.time.Instant.now().minus(cfg.cleanupGracePeriod())))) Files.delete(p);
            }
        }
    }
    public static String digest(Path p) throws IOException {
        MessageDigest d = sha256();
        try (var in = Files.newInputStream(p)) { byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) d.update(buffer, 0, n); }
        return HexFormat.of().formatHex(d.digest());
    }
    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

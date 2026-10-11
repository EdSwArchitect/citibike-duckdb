package com.bscllc.learning.citibike.db;

import com.bscllc.learning.citibike.config.CitibikeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

@ApplicationScoped
public class CsvResourceCatalog {
    static final String INDEX_RESOURCE = "META-INF/duckdb-learn/citibike-csv.index";
    private final CitibikeConfig config;
    private final URL applicationRoot;

    @Inject
    public CsvResourceCatalog(CitibikeConfig config) {
        this(config, applicationRoot());
    }

    CsvResourceCatalog(CitibikeConfig config, URL applicationRoot) {
        this.config = config;
        this.applicationRoot = applicationRoot;
    }

    // The plain resource record retains its code source even when Quarkus transforms CDI beans.
    // Resolve beside that class rather than through a global classpath lookup.
    private static URL applicationRoot() {
        var source = CsvResource.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("Application resource root is unavailable");
        }
        URL location = source.getLocation();
        try {
            return location.getPath().endsWith(".jar")
                    ? java.net.URI.create("jar:" + location.toExternalForm() + "!/").toURL()
                    : location;
        } catch (IOException e) {
            throw new IllegalStateException("Application resource root is unavailable");
        }
    }

    public List<CsvResource> discover() {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + config.csv().resourcePattern());
        SortedSet<String> names = new TreeSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resolve(INDEX_RESOURCE).openStream(), StandardCharsets.UTF_8))) {
            String name;
            while ((name = reader.readLine()) != null) {
                if (name.isEmpty()) {
                    continue;
                }
                validateTopLevelName(name);
                if (!names.add(name)) {
                    throw new IllegalStateException("Duplicate CSV resource name: " + name);
                }
            }
            List<CsvResource> resources = new ArrayList<>();
            for (String nameInIndex : names) {
                if (matcher.matches(Path.of(nameInIndex))) {
                    URL url = resolve(nameInIndex);
                    try (InputStream ignored = url.openStream()) {
                        resources.add(new CsvResource(nameInIndex, url));
                    }
                }
            }
            if (resources.isEmpty()) {
                throw new IllegalStateException("No top-level CSV resources matched the configured pattern");
            }
            return List.copyOf(resources);
        } catch (IOException e) {
            // Do not attach URL/path-bearing exceptions to startup logs.
            throw new IllegalStateException("Unable to read an indexed application CSV resource");
        }
    }

    private URL resolve(String name) throws IOException {
        return java.net.URI.create(applicationRoot.toExternalForm() + name.replace(" ", "%20")).toURL();
    }

    static void validateTopLevelName(String name) {
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,199}\\.csv") || name.contains("..")) {
            throw new IllegalStateException("Unsafe CSV resource name in application index");
        }
    }

    public record CsvResource(String name, URL url) {
        public InputStream openStream() throws IOException {
            return url.openStream();
        }
    }
}

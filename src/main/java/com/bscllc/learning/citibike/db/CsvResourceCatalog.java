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
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;

@ApplicationScoped
public class CsvResourceCatalog {
    static final String INDEX_RESOURCE = "META-INF/duckdb-learn/citibike-csv.index";

    private final CitibikeConfig config;
    private final ClassLoader classLoader;

    @Inject
    public CsvResourceCatalog(CitibikeConfig config) {
        this(config, Thread.currentThread().getContextClassLoader());
    }

    CsvResourceCatalog(CitibikeConfig config, ClassLoader classLoader) {
        this.config = config;
        this.classLoader = classLoader;
    }

    public List<CsvResource> discover() {
        PathMatcher matcher = FileSystems.getDefault()
                .getPathMatcher("glob:" + config.csv().resourcePattern());
        TreeSet<String> names = new TreeSet<>();

        try {
            Enumeration<URL> indexes = classLoader.getResources(INDEX_RESOURCE);
            while (indexes.hasMoreElements()) {
                readIndex(indexes.nextElement(), names);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read the CSV resource index", e);
        }

        List<CsvResource> resources = new ArrayList<>();
        for (String name : names) {
            validateTopLevelName(name);
            if (!matcher.matches(Path.of(name))) {
                continue;
            }
            URL resource = classLoader.getResource(name);
            if (resource == null) {
                throw new IllegalStateException("Indexed CSV resource is missing: " + name);
            }
            resources.add(new CsvResource(name, resource));
        }

        if (resources.isEmpty() && config.csv().failOnEmpty()) {
            throw new IllegalStateException(
                    "No top-level CSV resources matched " + config.csv().resourcePattern());
        }
        return List.copyOf(resources);
    }

    private static void readIndex(URL index, TreeSet<String> names) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(index.openStream(), StandardCharsets.UTF_8))) {
            reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .forEach(names::add);
        }
    }

    private static void validateTopLevelName(String name) {
        if (name.contains("/") || name.contains("\\") || name.contains("..")
                || !name.toLowerCase().endsWith(".csv")) {
            throw new IllegalStateException("Unsafe CSV resource name in index: " + name);
        }
    }

    public record CsvResource(String name, URL url) {
        public InputStream openStream() throws IOException {
            return url.openStream();
        }
    }
}

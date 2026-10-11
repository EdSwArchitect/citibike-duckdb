package com.bscllc.learning.citibike.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.jar.*;
import static com.bscllc.learning.citibike.support.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class CsvResourceCatalogTest {
    @TempDir Path directory;

    private CsvResourceCatalog catalog(String index) throws Exception {
        Path indexFile = directory.resolve(CsvResourceCatalog.INDEX_RESOURCE);
        Files.createDirectories(indexFile.getParent());
        Files.writeString(indexFile, index);
        return new CsvResourceCatalog(config(Map.of()), directory.toUri().toURL());
    }

    @Test void discoversAddedTopLevelCsvInSortedOrder() throws Exception {
        Files.writeString(directory.resolve("z.csv"), HEADER + ROW);
        Files.writeString(directory.resolve("a.csv"), HEADER + ROW);
        Files.createDirectories(directory.resolve("nested"));
        Files.writeString(directory.resolve("nested/ignored.csv"), HEADER + ROW);
        CsvResourceCatalog catalog = catalog("z.csv\na.csv\n");
        assertEquals(List.of("a.csv", "z.csv"), catalog.discover().stream().map(CsvResourceCatalog.CsvResource::name).toList());
        Files.writeString(directory.resolve("new.csv"), HEADER + ROW);
        Files.writeString(directory.resolve(CsvResourceCatalog.INDEX_RESOURCE), "z.csv\na.csv\nnew.csv\n");
        assertEquals(3, catalog.discover().size());
    }

    @Test void rejectsDuplicateNamesUnsafeNamesAndEmptyIndex() throws Exception {
        for (String index : List.of("a.csv\na.csv\n", "../a.csv\n", "nested/a.csv\n", "a\\b.csv\n", "a.csv\nmalicious\tname.csv\n", "")) {
            CsvResourceCatalog catalog = catalog(index);
            assertThrows(IllegalStateException.class, catalog::discover);
        }
        assertThrows(IllegalStateException.class, () -> CsvResourceCatalog.validateTopLevelName("bad\nname.csv"));
    }

    @Test void missingIndexOrIndexedCsvFails() throws Exception {
        assertThrows(IllegalStateException.class, new CsvResourceCatalog(config(Map.of()), directory.toUri().toURL())::discover);
        CsvResourceCatalog catalog = catalog("missing.csv\n");
        assertThrows(IllegalStateException.class, catalog::discover);
    }

    @Test void readsCsvFromSameJarAsIndexDespiteShadowingDependency() throws Exception {
        Path app = directory.resolve("app.jar");
        Path dependency = directory.resolve("dependency.jar");
        writeJar(app, Map.of(CsvResourceCatalog.INDEX_RESOURCE, "own.csv\n", "own.csv", "application"));
        writeJar(dependency, Map.of(CsvResourceCatalog.INDEX_RESOURCE, "dependency.csv\n", "dependency.csv", "dependency", "own.csv", "shadow"));
        try (URLClassLoader dependenciesFirst = new URLClassLoader(new URL[]{dependency.toUri().toURL(), app.toUri().toURL()}, null)) {
            assertEquals("shadow", new String(dependenciesFirst.getResourceAsStream("own.csv").readAllBytes()));
            CsvResourceCatalog catalog = new CsvResourceCatalog(config(Map.of()), new URL("jar:" + app.toUri().toURL() + "!/"));
            var resources = catalog.discover();
            assertEquals(List.of("own.csv"), resources.stream().map(CsvResourceCatalog.CsvResource::name).toList());
            try (var stream = resources.getFirst().openStream()) {
                assertEquals("application", new String(stream.readAllBytes()));
            }
        }
    }

    private void writeJar(Path path, Map<String, String> entries) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
    }
}

package com.bscllc.learning.citibike.db;

import com.bscllc.learning.citibike.config.CitibikeConfig;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.net.URL;

/** Selects the test artifact's index only in MultiFileProfile. Production never reads dependency indexes. */
@Alternative
@Dependent
public class TestCsvResourceCatalog extends CsvResourceCatalog {
    @Inject
    public TestCsvResourceCatalog(CitibikeConfig config) {
        super(config, root());
    }

    private static URL root() {
        String path = TestCsvResourceCatalog.class.getName().replace('.', '/') + ".class";
        String location = TestCsvResourceCatalog.class.getResource("TestCsvResourceCatalog.class").toExternalForm();
        try {
            return new URL(location.substring(0, location.length() - path.length()));
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.vwaves.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vwaves.mcp.config.RetrievalProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * The series catalogue backs the listSeries tool verbatim, so what matters is:
 * entries come back in ascending series order (the TSV may be edited out of
 * order), lookups miss to "" rather than null (callers concatenate the result),
 * and a missing/mangled file degrades to an empty catalogue instead of failing
 * startup.
 */
class SeriesCatalogServiceTest {

    @TempDir
    Path tempDir;

    /** Reference tunables with only the series-catalog path swapped in. */
    private static RetrievalProperties props(String seriesCatalogPath) {
        return new RetrievalProperties(
                5, 1, 4,                 // per-spec caps
                40, 10, 300, 4, 200,     // pool sizes
                0.15, 1.5, 60, 0.5, 0.5, 1.3,   // scoring
                1.25, 0.85,              // release
                10,                      // agreement top-N
                0.12, 0.25, 0.02,        // confidence margins
                2, 1500,                 // stub suppression
                4,                       // and-term limit
                700, 1000,               // study-report range
                "classpath:retrieval/stop-words.txt",
                "classpath:retrieval/and-term-subst.tsv",
                "classpath:retrieval/non-3gpp-intent-terms.txt",
                "classpath:retrieval/spec-ownership.tsv",
                0.30, 0.45, "classpath:retrieval/test-spec-prefixes.txt",
                "classpath:retrieval/procedure-layers.tsv",
                "classpath:retrieval/procedure-synonyms.tsv",
                seriesCatalogPath, 1.35,
                "classpath:retrieval/intents.tsv",
                "classpath:retrieval/intent-exemplars.tsv", 0.50);
    }

    private SeriesCatalogService service(String tsvContent) throws Exception {
        Path tsv = tempDir.resolve("series-catalog.tsv");
        Files.writeString(tsv, tsvContent);
        return new SeriesCatalogService(new DefaultResourceLoader(),
                props(tsv.toUri().toString()));
    }

    @Nested
    @DisplayName("with a populated catalogue")
    class Populated {

        // Deliberately out of order in the file — ordering must come from the
        // service, not from whoever last edited the TSV.
        private static final String TSV = """
                # 3GPP series catalogue
                38\tNR radio access
                23\tArchitecture and stage-2
                29\tCore network protocols

                malformed line without a tab
                """;

        @Test
        void describeReturnsTheCatalogueDescription() throws Exception {
            SeriesCatalogService s = service(TSV);
            assertEquals("Architecture and stage-2", s.describe("23"));
            assertEquals("NR radio access", s.describe("38"));
        }

        @Test
        void unknownSeriesDescribesAsEmptyStringNeverNull() throws Exception {
            assertEquals("", service(TSV).describe("99"));
        }

        @Test
        void entriesComeBackInAscendingSeriesOrder() throws Exception {
            List<String> keys = service(TSV).entries().stream()
                    .map(Map.Entry::getKey)
                    .toList();
            assertEquals(List.of("23", "29", "38"), keys,
                    "file order is 38,23,29 — output must be sorted");
        }

        @Test
        void commentsBlanksAndMalformedLinesAreSkipped() throws Exception {
            assertEquals(3, service(TSV).entries().size());
        }
    }

    @Nested
    @DisplayName("degraded configurations")
    class Degraded {

        @Test
        void missingFileYieldsAnEmptyCatalogueNotAFailure() {
            SeriesCatalogService s = new SeriesCatalogService(new DefaultResourceLoader(),
                    props("file:" + tempDir.resolve("no-such-catalog.tsv")));
            assertTrue(s.entries().isEmpty());
            assertEquals("", s.describe("23"));
        }

        @Test
        void emptyFileYieldsAnEmptyCatalogue() throws Exception {
            assertTrue(service("").entries().isEmpty());
        }

        @Test
        void entriesWithEmptyKeyOrValueAreDropped() throws Exception {
            SeriesCatalogService s = service("23\t\n\tOrphan description\n29\tCore protocols\n");
            assertEquals(1, s.entries().size());
            assertFalse(s.describe("29").isEmpty());
        }
    }

    @Test
    @DisplayName("the shipped classpath catalogue loads and is non-trivial")
    void shippedCatalogueLoads() {
        // Guards the packaged resource itself: a bad edit to the TSV (or a rename)
        // would silently empty the listSeries tool.
        SeriesCatalogService s = new SeriesCatalogService(new DefaultResourceLoader(),
                props("classpath:retrieval/series-catalog.tsv"));
        assertFalse(s.entries().isEmpty(), "shipped series-catalog.tsv must parse to entries");
        assertFalse(s.describe("23").isEmpty(), "series 23 must be described in the shipped file");
    }
}

package com.vwaves.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vwaves.mcp.config.RetrievalProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * ProcedureLayerService is pure table-driven config: a layer table (which spec
 * series to retrieve from, per technology) and a synonym table (how to phrase
 * the query). Both tables here are written by the test itself, so every
 * assertion is against content we control — the shipped tsv is only smoke-tested
 * for parseability at the end.
 */
class ProcedureLayerServiceTest {

    @TempDir
    Path dir;

    private ProcedureLayerService service;

    private static final String LAYERS = """
            # test layer table — layer <TAB> series <TAB> order <TAB> tech <TAB> perLayer <TAB> prefer <TAB> label
            alias\tlte\tlte
            alias\t4g\tlte
            alias\tnr\t5g
            alias\tboth\tboth
            layer\t23\t1\t5G\t4\t23.5\tSystem procedures (5G)
            layer\t23\t1\tLTE\t4\t23.4\tSystem procedures (EPS)
            layer\t24\t2\tANY\t3\t-\tNAS signalling
            layer\t38\t3\t5G\t2\t38.3,38.5\tRadio (NR)
            layer\t36\t3\tLTE\t2\t36.3\tRadio (LTE)
            layer\txx\toops\t5G\t2\t-\tnon-numeric order — must be skipped
            layer\ttoo\tshort
            junk\twhatever\trow
            """;

    private static final String SYNONYMS = """
            # test synonym table
            registration\tinitial registration attach request
            handover\tho mobility
            no tab on this line
            """;

    private static RetrievalProperties props(String layersPath, String synonymsPath) {
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
                layersPath,
                synonymsPath,
                "classpath:retrieval/series-catalog.tsv", 1.35,
                "classpath:retrieval/intents.tsv",
                "classpath:retrieval/intent-exemplars.tsv", 0.50);
    }

    private String write(String name, String content) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, content);
        return f.toUri().toString();
    }

    @BeforeEach
    void setUp() throws IOException {
        service = new ProcedureLayerService(new DefaultResourceLoader(),
                props(write("layers.tsv", LAYERS), write("synonyms.tsv", SYNONYMS)));
    }

    @Test
    void loadsValidRowsAndSkipsMalformedOnes() {
        // 5 valid layer rows; the non-numeric, short and unknown-kind rows are dropped.
        assertEquals(5, service.layerCount());
        assertEquals(2, service.synonymCount());
    }

    @Nested
    @DisplayName("technology aliasing")
    class Aliasing {

        @Test
        void nullAndBlankFallBackToFiveG() {
            assertEquals("5g", service.canonicalTechnology(null));
            assertEquals("5g", service.canonicalTechnology("   "));
        }

        @Test
        void aliasesMatchCaseInsensitivelyInsideLongerWording() {
            assertEquals("lte", service.canonicalTechnology("LTE handover"));
            assertEquals("lte", service.canonicalTechnology("4G attach"));
            assertEquals("5g", service.canonicalTechnology("5G NR mobility"));
            assertEquals("both", service.canonicalTechnology("both"));
        }

        @Test
        void unrecognisedTechnologyFallsBackToFiveG() {
            assertEquals("5g", service.canonicalTechnology("wimax"));
        }
    }

    @Nested
    @DisplayName("layersFor: technology filter + display order")
    class LayersFor {

        @Test
        void fiveGGetsFiveGAndAnyRowsInOrder() {
            List<ProcedureLayerService.Layer> l = service.layersFor("5g nr");
            assertEquals(3, l.size());
            assertEquals("23", l.get(0).series());
            assertEquals("System procedures (5G)", l.get(0).label());
            assertEquals("24", l.get(1).series());   // ANY row applies everywhere
            assertEquals("38", l.get(2).series());
            assertEquals(2, l.get(2).perLayer());
        }

        @Test
        void lteGetsLteAndAnyRows() {
            List<ProcedureLayerService.Layer> l = service.layersFor("LTE");
            assertEquals(3, l.size());
            assertEquals("System procedures (EPS)", l.get(0).label());
            assertEquals("24", l.get(1).series());
            assertEquals("36", l.get(2).series());
        }

        @Test
        void bothReturnsEveryRowSortedByOrderThenSeries() {
            List<ProcedureLayerService.Layer> l = service.layersFor("both");
            assertEquals(5, l.size());
            // order 1 first (both series-23 rows), then 24, then 36 before 38.
            assertEquals("23", l.get(0).series());
            assertEquals("23", l.get(1).series());
            assertEquals("24", l.get(2).series());
            assertEquals("36", l.get(3).series());
            assertEquals("38", l.get(4).series());
        }

        @Test
        void nullTechnologyBehavesLikeFiveG() {
            assertEquals(service.layersFor("5g"), service.layersFor(null));
        }
    }

    @Nested
    @DisplayName("expandProcedure appends, never substitutes")
    class Expansion {

        @Test
        void appendsTheSynonymAfterTheOriginalWording() {
            assertEquals("UE Registration Procedure initial registration attach request",
                    service.expandProcedure("UE Registration Procedure"));
        }

        @Test
        void matchIsCaseInsensitive() {
            assertEquals("REGISTRATION initial registration attach request",
                    service.expandProcedure("REGISTRATION"));
        }

        @Test
        void multipleMatchesAppendInTableOrder() {
            assertEquals("registration then handover initial registration attach request ho mobility",
                    service.expandProcedure("registration then handover"));
        }

        @Test
        void noMatchLeavesTheQueryUntouched() {
            assertEquals("paging procedure", service.expandProcedure("paging procedure"));
        }

        @Test
        void nullAndBlankPassThrough() {
            assertNull(service.expandProcedure(null));
            assertEquals("   ", service.expandProcedure("   "));
        }
    }

    @Nested
    @DisplayName("isPreferred: prefix match against the layer's prefer list")
    class Preferred {

        private ProcedureLayerService.Layer radioNr() {
            return service.layersFor("5g").get(2);   // series 38, prefer 38.3,38.5
        }

        private ProcedureLayerService.Layer nas() {
            return service.layersFor("5g").get(1);   // series 24, prefer "-" → empty
        }

        @Test
        void matchesAnyConfiguredPrefix() {
            assertTrue(ProcedureLayerService.isPreferred(radioNr(), "38.331"));
            assertTrue(ProcedureLayerService.isPreferred(radioNr(), "38.501"));
        }

        @Test
        void rejectsSpecsOutsideThePreferredFamilies() {
            assertFalse(ProcedureLayerService.isPreferred(radioNr(), "36.331"));
            assertFalse(ProcedureLayerService.isPreferred(radioNr(), "23.502"));
        }

        @Test
        void dashInTheTableMeansNoPreference() {
            assertTrue(nas().prefer().isEmpty());
            assertFalse(ProcedureLayerService.isPreferred(nas(), "24.301"));
        }

        @Test
        void nullSpecIdIsNeverPreferred() {
            assertFalse(ProcedureLayerService.isPreferred(radioNr(), null));
        }
    }

    @Nested
    @DisplayName("degrades gracefully when the tables are missing")
    class MissingTables {

        @Test
        void missingFilesYieldAnEmptyButWorkingService() {
            String gone = dir.resolve("does-not-exist.tsv").toUri().toString();
            ProcedureLayerService empty =
                    new ProcedureLayerService(new DefaultResourceLoader(), props(gone, gone));
            assertEquals(0, empty.layerCount());
            assertEquals(0, empty.synonymCount());
            assertTrue(empty.layersFor("5g").isEmpty());
            assertTrue(empty.layersFor("both").isEmpty());
            // With no synonym table, expansion is the identity function.
            assertEquals("registration", empty.expandProcedure("registration"));
            // With no alias table, everything canonicalises to the 5g fallback.
            assertEquals("5g", empty.canonicalTechnology("lte"));
        }
    }

    @Test
    @DisplayName("the shipped classpath tables parse")
    void shippedTablesParse() {
        ProcedureLayerService shipped = new ProcedureLayerService(new DefaultResourceLoader(),
                props("classpath:retrieval/procedure-layers.tsv",
                      "classpath:retrieval/procedure-synonyms.tsv"));
        assertTrue(shipped.layerCount() > 0, "shipped layer table must not be empty");
        for (ProcedureLayerService.Layer l : shipped.layersFor("both")) {
            assertFalse(l.series().isBlank());
            assertFalse(l.label().isBlank());
            assertTrue(l.perLayer() > 0, "perLayer must be positive for " + l.series());
        }
    }
}

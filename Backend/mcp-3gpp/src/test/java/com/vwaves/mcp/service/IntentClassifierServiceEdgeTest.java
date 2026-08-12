package com.vwaves.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vwaves.mcp.config.RetrievalProperties;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Covers the {@link IntentClassifierService} corners its main test leaves out:
 * the IO-failure branch of init(), the no-exemplar warning sweep, the
 * defensive cosine guard (mismatched vector lengths), and the hand-written
 * equals/hashCode/toString contract of the private Exemplar record (hand-written
 * because records compare float[] fields by reference, not content).
 */
class IntentClassifierServiceEdgeTest {

    private static final double FLOOR = 0.50;

    /** Table-driven embedding stub; unknown texts embed to the zero vector. */
    static final class FixedEmbeddingService extends EmbeddingService {
        final Map<String, float[]> table = new HashMap<>();

        FixedEmbeddingService() {
            super(null, "stub-model", 2);
        }

        @Override
        public float[] embed(String text) {
            return table.getOrDefault(text, new float[]{0f, 0f});
        }
    }

    @TempDir
    Path dir;

    private static final String INTENTS = """
            intent\tgeneral\t100\tfalse\tgeneral 3GPP question
            intent\torphan\t20\tfalse\tnon-sticky intent WITHOUT exemplars
            intent\tvendor_command\t10\ttrue\tsticky intent without exemplars
            term\torphan\torphan term
            """;

    private static final String EXEMPLARS = """
            general\twhat is a network function
            """;

    private static RetrievalProperties props(String intentsPath, String exemplarsPath) {
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
                "classpath:retrieval/series-catalog.tsv", 1.35,
                intentsPath,
                exemplarsPath, FLOOR);
    }

    private String write(String name, String content) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, content);
        return f.toUri().toString();
    }

    // ── init() edge branches ─────────────────────────────────────────────────

    @Nested
    @DisplayName("init() degradation and warning sweep")
    class InitBranches {

        @Test
        void ioFailureMidReadLeavesTheClassifierEmptyWithoutThrowing() throws IOException {
            IntentCatalogService catalog = new IntentCatalogService(
                    new DefaultResourceLoader(), props(write("i1.tsv", INTENTS),
                            "unused"));
            // The classifier reads exemplars through ITS OWN loader — hand it
            // one whose stream fails mid-read.
            IntentClassifierService classifier = new IntentClassifierService(
                    new FailingResourceSupport.FailingResourceLoader(),
                    new FixedEmbeddingService(), catalog,
                    props(write("i2.tsv", INTENTS), "stub:exemplars.tsv"));

            classifier.init();   // must not throw

            assertEquals(0, classifier.exemplarCount());
            assertEquals("keyword", classifier.classify("orphan term please").method());
        }

        @Test
        void intentsWithoutExemplarsAreToleratedStickyOrNot() throws IOException {
            // The non-sticky 'orphan' intent with no exemplars triggers the
            // warning sweep, while the sticky 'vendor_command' is exempted from
            // it. Both must leave init() working and the one real exemplar loaded.
            RetrievalProperties p = props(write("i3.tsv", INTENTS),
                    write("e3.tsv", EXEMPLARS));
            FixedEmbeddingService embedding = new FixedEmbeddingService();
            embedding.table.put("what is a network function", new float[]{0f, 1f});
            IntentCatalogService catalog = new IntentCatalogService(new DefaultResourceLoader(), p);
            IntentClassifierService classifier = new IntentClassifierService(
                    new DefaultResourceLoader(), embedding, catalog, p);

            classifier.init();

            assertEquals(1, classifier.exemplarCount());
            var r = classifier.classify("what is a network function");
            assertEquals("general", r.intent().id());
            assertEquals("embedding", r.method());
        }
    }

    // ── cosine guard ─────────────────────────────────────────────────────────

    @Test
    void mismatchedVectorLengthsScoreMinusOneAndFallBackToKeywords() throws IOException {
        RetrievalProperties p = props(write("i4.tsv", INTENTS), write("e4.tsv", EXEMPLARS));
        FixedEmbeddingService embedding = new FixedEmbeddingService();
        embedding.table.put("what is a network function", new float[]{0f, 1f});
        // The query embeds to a 3-dim vector against 2-dim exemplars — the
        // guard must yield -1 for every exemplar, so no "best" ever exists.
        embedding.table.put("dimension drift query", new float[]{1f, 0f, 0f});
        IntentCatalogService catalog = new IntentCatalogService(new DefaultResourceLoader(), p);
        IntentClassifierService classifier = new IntentClassifierService(
                new DefaultResourceLoader(), embedding, catalog, p);
        classifier.init();

        var r = classifier.classify("dimension drift query");

        assertEquals("keyword-fallback", r.method());
        assertEquals(-1.0, r.similarity(), 1e-9);
        assertEquals("general", r.intent().id(), "no keyword hit → catalogue default");
    }

    // ── Exemplar record contract (reflection: the record is private) ────────

    @Nested
    @DisplayName("Exemplar equals/hashCode/toString")
    class ExemplarContract {

        private Constructor<?> ctor() throws Exception {
            Class<?> c = Class.forName(
                    "com.vwaves.mcp.service.IntentClassifierService$Exemplar");
            Constructor<?> ctor = c.getDeclaredConstructor(
                    IntentCatalogService.Intent.class, String.class, float[].class);
            ctor.setAccessible(true);
            return ctor;
        }

        private Object exemplar(String intentId, String text, float... vector) throws Exception {
            IntentCatalogService.Intent intent =
                    new IntentCatalogService.Intent(intentId, 10, false, "hint");
            return ctor().newInstance(intent, text, vector);
        }

        @Test
        void equalsComparesVectorContentNotReference() throws Exception {
            // Two distinct float[] instances with the same content — the whole
            // reason equals is hand-written instead of record-generated.
            Object a = exemplar("general", "hello", 1f, 2f);
            Object b = exemplar("general", "hello", 1f, 2f);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode(),
                    "equal exemplars must share a hash code");
        }

        @Test
        void equalsIsReflexiveAndNullAndForeignSafe() throws Exception {
            Object a = exemplar("general", "hello", 1f, 2f);
            assertEquals(a, a);
            assertNotEquals(null, a);
            assertNotEquals("not an exemplar", a);
        }

        @Test
        void anyDifferingComponentBreaksEquality() throws Exception {
            Object base = exemplar("general", "hello", 1f, 2f);
            assertNotEquals(base, exemplar("orphan", "hello", 1f, 2f));
            assertNotEquals(base, exemplar("general", "other", 1f, 2f));
            assertNotEquals(base, exemplar("general", "hello", 9f, 2f));
        }

        @Test
        void toStringShowsAllThreeComponentsIncludingVectorValues() throws Exception {
            String s = exemplar("general", "hello", 1f, 2f).toString();
            assertTrue(s.startsWith("Exemplar["), s);
            assertTrue(s.contains("text=hello"), s);
            assertTrue(s.contains("vector=[1.0, 2.0]"),
                    "the vector must print its CONTENT, not an array identity: " + s);
            assertTrue(s.contains("general"), s);
        }
    }
}

package com.vwaves.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vwaves.mcp.config.RetrievalProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * The classifier's decision order is the contract under test:
 *   1. a sticky keyword hit wins outright,
 *   2. otherwise the nearest exemplar when its cosine clears the floor,
 *   3. otherwise the catalogue's keyword rules.
 * The ONNX model never loads here — a stub EmbeddingService hands back
 * hand-built unit vectors, so every cosine in these tests is exact by design
 * (EmbeddingService guarantees L2-normalised output; the stub honours that).
 */
class IntentClassifierServiceTest {

    private static final double FLOOR = 0.50;

    /**
     * Table-driven embedding stub. Also records every text it is asked to embed,
     * which lets the tests assert that the sticky-keyword path never pays for an
     * embedding. Unknown texts map to the zero vector (cosine 0 with everything).
     */
    static final class StubEmbeddingService extends EmbeddingService {
        final Map<String, float[]> table = new LinkedHashMap<>();
        final List<String> embeddedTexts = new ArrayList<>();

        StubEmbeddingService() {
            super(null, "stub-model", 2);
        }

        @Override
        public float[] embed(String text) {
            embeddedTexts.add(text);
            return table.getOrDefault(text, new float[]{0f, 0f});
        }
    }

    @TempDir
    Path dir;

    private StubEmbeddingService embedding;
    private IntentCatalogService catalog;
    private IntentClassifierService classifier;

    /** vendor_command is sticky; general (highest priority value) is the default. */
    private static final String INTENTS = """
            # test catalogue
            intent\tvendor_command\t10\ttrue\tvendor CLI or commercial question
            intent\tprocedure_flow\t20\tfalse\tprocedure walk-through
            intent\tgeneral\t100\tfalse\tgeneral 3GPP question
            term\tvendor_command\tcli command
            weak\tvendor_command\tnokia
            weak\tvendor_command\thuawei
            term\tprocedure_flow\tcall flow
            """;

    private static final String EXEMPLARS = """
            # one exemplar per axis, plus rows the loader must reject
            procedure_flow\twalk me through registration
            general\twhat is a network function
            no_such_intent\tdead exemplar for an undeclared intent
            a row without any tab separator
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

    @BeforeEach
    void setUp() throws IOException {
        RetrievalProperties p = props(write("intents.tsv", INTENTS),
                                      write("exemplars.tsv", EXEMPLARS));
        embedding = new StubEmbeddingService();
        // Exemplar space: procedure_flow sits on the x axis, general on the y axis.
        embedding.table.put("walk me through registration", new float[]{1f, 0f});
        embedding.table.put("what is a network function", new float[]{0f, 1f});
        catalog = new IntentCatalogService(new DefaultResourceLoader(), p);
        classifier = new IntentClassifierService(new DefaultResourceLoader(), embedding, catalog, p);
    }

    @Test
    void initEmbedsDeclaredExemplarsAndRejectsBadRows() {
        classifier.init();
        // The undeclared-intent row and the tab-less row must both be dropped.
        assertEquals(2, classifier.exemplarCount());
        assertFalse(embedding.embeddedTexts.contains("dead exemplar for an undeclared intent"),
                "an exemplar for an undeclared intent must be skipped, not embedded");
    }

    @Nested
    @DisplayName("decision order")
    class DecisionOrder {

        @BeforeEach
        void initClassifier() {
            classifier.init();
        }

        @Test
        void stickyKeywordHitWinsWithoutConsultingTheEmbedding() {
            var r = classifier.classify("show me the cli command for amf backup");
            assertEquals("vendor_command", r.intent().id());
            assertEquals("keyword-sticky", r.method());
            assertEquals(1.0, r.similarity(), 1e-9);
            assertFalse(embedding.embeddedTexts.contains("show me the cli command for amf backup"),
                    "a sticky keyword decision must not pay for an embedding");
        }

        @Test
        void twoWeakTermsTogetherAlsoTriggerTheStickyIntent() {
            var r = classifier.classify("nokia and huawei configuration comparison");
            assertEquals("vendor_command", r.intent().id());
            assertEquals("keyword-sticky", r.method());
        }

        @Test
        void nearestExemplarWinsWhenAboveTheFloor() {
            embedding.table.put("how does the ue register with the network",
                    new float[]{0.8f, 0.6f});   // cos: 0.8 vs procedure_flow, 0.6 vs general
            var r = classifier.classify("how does the ue register with the network");
            assertEquals("procedure_flow", r.intent().id());
            assertEquals("embedding", r.method());
            assertEquals(0.8, r.similarity(), 1e-6);
        }

        @Test
        void embeddingOverridesANonStickyKeywordHit() {
            // "call flow" is a procedure_flow keyword, but the embedding sits
            // exactly on the general exemplar — for a non-sticky intent the
            // embedding answer must win.
            embedding.table.put("call flow overview of the architecture", new float[]{0f, 1f});
            var r = classifier.classify("call flow overview of the architecture");
            assertEquals("general", r.intent().id());
            assertEquals("embedding", r.method());
            assertEquals(1.0, r.similarity(), 1e-9);
        }

        @Test
        void belowFloorSimilarityFallsBackToKeywordRules() {
            // Best cosine is 0.4 (vs general) — under the 0.50 floor, and the
            // query carries no keyword, so the default intent is returned with
            // the too-weak similarity reported for observability.
            embedding.table.put("completely unrelated gibberish", new float[]{0.3f, 0.4f});
            var r = classifier.classify("completely unrelated gibberish");
            assertEquals("general", r.intent().id());
            assertEquals("keyword-fallback", r.method());
            assertEquals(0.4, r.similarity(), 1e-6);
        }

        @Test
        void belowFloorStillHonoursNonStickyKeywords() {
            embedding.table.put("call flow please",
                    new float[]{0.1f, 0.2f});   // best 0.2 — far under the floor
            var r = classifier.classify("call flow please");
            assertEquals("procedure_flow", r.intent().id());
            assertEquals("keyword-fallback", r.method());
        }

        @Test
        void similarityIsRoundedToThreeDecimals() {
            embedding.table.put("registration walkthrough", new float[]{0.7654321f, 0f});
            var r = classifier.classify("registration walkthrough");
            assertEquals("embedding", r.method());
            assertEquals(0.765, r.similarity(), 1e-9);
        }
    }

    @Nested
    @DisplayName("degraded modes")
    class DegradedModes {

        @Test
        void nullAndBlankQueriesReturnTheDefaultIntent() {
            classifier.init();
            var r = classifier.classify(null);
            assertEquals("general", r.intent().id());
            assertEquals("default", r.method());
            assertEquals(0.0, r.similarity(), 1e-9);
            assertEquals("default", classifier.classify("   ").method());
        }

        @Test
        void beforeInitEveryQueryUsesKeywordRulesOnly() {
            // init() never called — the exemplar list is empty by construction.
            var r = classifier.classify("call flow of paging");
            assertEquals("procedure_flow", r.intent().id());
            assertEquals("keyword", r.method());
            assertEquals(0.0, r.similarity(), 1e-9);
            assertTrue(embedding.embeddedTexts.isEmpty(),
                    "with no exemplars the query must not be embedded at all");
        }

        @Test
        void missingExemplarFileLeavesTheKeywordPathInCharge() throws IOException {
            RetrievalProperties p = props(write("intents2.tsv", INTENTS),
                    dir.resolve("no-such-exemplars.tsv").toUri().toString());
            IntentClassifierService bare = new IntentClassifierService(
                    new DefaultResourceLoader(), embedding, catalog, p);
            bare.init();   // must not throw
            assertEquals(0, bare.exemplarCount());
            assertEquals("keyword", bare.classify("call flow of paging").method());
        }

        @Test
        void unmatchedQueryFallsThroughToTheDefaultIntent() {
            // Uninitialised classifier + no keyword hit → the catalogue default.
            var r = classifier.classify("zzz nothing matches this");
            assertEquals("general", r.intent().id());
            assertEquals("keyword", r.method());
        }
    }
}

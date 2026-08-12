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
 * Direct tests for {@link IntentCatalogService} (previously only exercised
 * indirectly through the classifier): the TSV parser's malformed-row handling,
 * the priority ordering / default-slot contract, the id lookups, the weak-term
 * company rule, and the never-throws degradation when the catalogue file is
 * missing or unreadable.
 */
class IntentCatalogServiceTest {

    @TempDir
    Path dir;

    private IntentCatalogService catalog;

    /**
     * 2 valid intents + every malformed shape the parser must skip:
     * non-numeric priority, too few columns, unknown row kind, empty term,
     * and a term row naming an undeclared intent.
     */
    private static final String INTENTS = """
            # test catalogue
            intent\tclause_lookup\t10\ttrue\tlook up a specific clause
            intent\tgeneral\t100\tfalse\tgeneral 3GPP question
            intent\tbad_priority\toops\tfalse\tmust be skipped
            intent\ttoo_short
            wibble\tunknown\trow\tkind
            term\tclause_lookup\tclause
            term\tclause_lookup\t\tempty term column
            weak\tgeneral\talpha
            weak\tgeneral\tbeta
            term\tghost_intent\tspooky
            """;

    private static RetrievalProperties props(String intentsPath) {
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
                "classpath:retrieval/intent-exemplars.tsv", 0.50);
    }

    private String write(String name, String content) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, content);
        return f.toUri().toString();
    }

    @BeforeEach
    void setUp() throws IOException {
        catalog = new IntentCatalogService(new DefaultResourceLoader(),
                props(write("intents.tsv", INTENTS)));
    }

    @Nested
    @DisplayName("catalogue parsing")
    class Parsing {

        @Test
        void keepsValidIntentsAndDropsMalformedRows() {
            List<IntentCatalogService.Intent> intents = catalog.intents();
            assertEquals(2, intents.size(),
                    "bad_priority, too_short and the unknown kind must all be dropped");
            assertEquals("clause_lookup", intents.get(0).id());
            assertEquals("general", intents.get(1).id());
        }

        @Test
        void intentAttributesSurviveTheRoundTrip() {
            IntentCatalogService.Intent clause = catalog.byId("clause_lookup");
            assertEquals(10, clause.priority());
            assertTrue(clause.sticky());
            assertEquals("look up a specific clause", clause.hint());
        }

        @Test
        void labelReplacesUnderscoresWithDashes() {
            assertEquals("clause-lookup", catalog.byId("clause_lookup").label());
        }

        @Test
        void termsForAnUndeclaredIntentNeverMatch() {
            // "spooky" belongs to ghost_intent, which was never declared, so it
            // must be dead config rather than resurrect an unknown intent.
            assertNull(catalog.keywordMatch("something spooky happened"));
        }
    }

    @Nested
    @DisplayName("lookups")
    class Lookups {

        @Test
        void byIdIsCaseInsensitive() {
            assertEquals("clause_lookup", catalog.byId("CLAUSE_LOOKUP").id());
        }

        @Test
        void byIdFallsBackToTheDefaultForUnknownAndNull() {
            assertEquals("general", catalog.byId("no_such_intent").id());
            assertEquals("general", catalog.byId(null).id());
        }

        @Test
        void isKnownChecksDeclarationNotTermTables() {
            assertTrue(catalog.isKnown("clause_lookup"));
            assertTrue(catalog.isKnown("GENERAL"));
            assertFalse(catalog.isKnown("ghost_intent"));
            assertFalse(catalog.isKnown(null));
        }

        @Test
        void highestPriorityValueOwnsTheDefaultSlot() {
            assertEquals("general", catalog.defaultIntent().id());
        }
    }

    @Nested
    @DisplayName("keyword classification")
    class KeywordClassification {

        @Test
        void strongTermMatchesAlone() {
            assertEquals("clause_lookup",
                    catalog.keywordMatch("which clause covers paging").id());
        }

        @Test
        void oneWeakTermIsNotEnough() {
            assertNull(catalog.keywordMatch("alpha configuration"));
        }

        @Test
        void twoWeakTermsTogetherClassify() {
            assertEquals("general",
                    catalog.keywordMatch("alpha and beta comparison").id());
        }

        @Test
        void nullAndBlankNeverMatch() {
            assertNull(catalog.keywordMatch(null));
            assertNull(catalog.keywordMatch("   "));
        }

        @Test
        void classifyByKeywordAppliesTheDefaultOnMiss() {
            assertEquals("general", catalog.classifyByKeyword("zzz nothing here").id());
            assertEquals("clause_lookup", catalog.classifyByKeyword("clause 4.2.2").id());
        }
    }

    @Nested
    @DisplayName("degraded catalogues")
    class Degraded {

        @Test
        void missingFileYieldsTheUnknownFallbackIntent() {
            IntentCatalogService empty = new IntentCatalogService(new DefaultResourceLoader(),
                    props(dir.resolve("no-such-intents.tsv").toUri().toString()));
            assertTrue(empty.intents().isEmpty());
            IntentCatalogService.Intent unknown = empty.defaultIntent();
            assertEquals("unknown", unknown.id());
            assertFalse(unknown.sticky());
            assertEquals("unknown", empty.classifyByKeyword("any query at all").id());
            assertNull(empty.keywordMatch("any query at all"));
            assertFalse(empty.isKnown("unknown"),
                    "the UNKNOWN fallback is not a declared intent");
        }

        @Test
        void ioFailureMidReadDegradesTheSameWay() {
            IntentCatalogService broken = new IntentCatalogService(
                    new FailingResourceSupport.FailingResourceLoader(),
                    props("stub:intents.tsv"));
            assertTrue(broken.intents().isEmpty());
            assertEquals("unknown", broken.defaultIntent().id());
        }
    }
}

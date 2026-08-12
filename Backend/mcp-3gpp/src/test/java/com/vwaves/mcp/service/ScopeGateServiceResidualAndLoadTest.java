package com.vwaves.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vwaves.mcp.config.RetrievalProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Covers the {@link ScopeGateService} paths that {@code ScopeGateServiceTest}
 * (which runs against the shipped table) leaves out: the partial-coverage
 * ("note") half of the gate — {@code coverageCaveat} and {@code residualQuery} —
 * the RFC display formatting, the TSV parser's malformed-row handling, and the
 * missing-file / IO-failure degradation branches. The table here is written by
 * the test itself so every branch is deterministic.
 */
class ScopeGateServiceResidualAndLoadTest {

    @TempDir
    Path dir;

    private ScopeGateService gate;

    /**
     * 5 valid rows (3 block, 2 note) plus every malformed shape the parser must
     * skip: comment, blank, single-column, empty marker, empty spec.
     */
    private static final String OWNERSHIP = """
            # marker <TAB> owning spec <TAB> topic <TAB> mode
            pfcp\t29.244\tPFCP protocol
            n4 session\t29.244\tPFCP protocol\tblock
            mpls-tp\t28.999
            pseudowire\tRFC3985\tPWE3 architecture\tnote
            twamp light\tRFC5357\tTWAMP measurement\tNOTE

            just-one-column
            \t29.500
            orphan-marker\t \tblank spec column
            """;

    private static RetrievalProperties props(String ownershipPath) {
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
                ownershipPath,
                0.30, 0.45, "classpath:retrieval/test-spec-prefixes.txt",
                "classpath:retrieval/procedure-layers.tsv",
                "classpath:retrieval/procedure-synonyms.tsv",
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
        gate = new ScopeGateService(new DefaultResourceLoader(), props(write("own.tsv", OWNERSHIP)));
        gate.resolveAgainstIndex(Set.of());   // nothing indexed — every marker live
    }

    @Nested
    @DisplayName("TSV parsing")
    class Parsing {

        @Test
        void keepsValidRowsAndDropsMalformedOnes() {
            // 5 usable rows; comment, blank, one-column, empty-marker and
            // empty-spec rows are all rejected.
            assertEquals(5, gate.markerCount());
            assertEquals(5, gate.activeMarkerCount());
        }

        @Test
        void noteModeIsCaseInsensitive() {
            // "NOTE" (upper case) must be treated as note, not block.
            assertNull(gate.outOfScopeReason("twamp light delay measurement"));
            assertNotNull(gate.coverageCaveat("twamp light delay measurement"));
        }

        @Test
        void twoColumnRowFallsBackToTheSpecIdAsTopic() {
            // "mpls-tp" row has no topic column — the block message must show
            // the spec id in the topic slot rather than crash or show null.
            String r = gate.outOfScopeReason("mpls-tp protection switching");
            assertNotNull(r);
            assertTrue(r.contains("TS 28.999 — 28.999"),
                    "spec id must double as the topic: " + r);
        }
    }

    @Nested
    @DisplayName("partial-coverage (note) markers")
    class PartialCoverage {

        @Test
        void noteMarkerCaveatsInsteadOfBlocking() {
            String q = "pseudowire down leading to link down";
            assertNull(gate.outOfScopeReason(q), "a note marker must never block");
            String caveat = gate.coverageCaveat(q);
            assertNotNull(caveat);
            assertTrue(caveat.contains("Partial coverage"), caveat);
            assertTrue(caveat.contains("pseudowire"), caveat);
            assertTrue(caveat.contains("WebSearch"), caveat);
        }

        @Test
        void rfcSpecIdsAreDisplayedAsRfcNotTs() {
            String caveat = gate.coverageCaveat("pseudowire status");
            assertNotNull(caveat);
            assertTrue(caveat.contains("RFC 3985"),
                    "RFC3985 must render as 'RFC 3985': " + caveat);
            assertFalse(caveat.contains("TS RFC"),
                    "an RFC id must not get the TS prefix: " + caveat);
        }

        @Test
        void blockingMarkerOutranksANoteMarkerInTheSameQuery() {
            String q = "pfcp session for the pseudowire service";
            assertNotNull(gate.outOfScopeReason(q), "block must win over note");
            assertNull(gate.coverageCaveat(q), "a blocked query has no caveat");
        }

        @Test
        void caveatIsNullForBlockedAndForCleanQueries() {
            assertNull(gate.coverageCaveat("pfcp association recovery"));
            assertNull(gate.coverageCaveat("pdu session establishment"));
            assertNull(gate.coverageCaveat(null));
        }

        @Test
        void noteMarkerDisablesItselfOnceItsSpecIsIndexed() {
            gate.resolveAgainstIndex(Set.of("RFC3985"));
            assertNull(gate.coverageCaveat("pseudowire status"));
            // The other four markers stay live.
            assertEquals(4, gate.activeMarkerCount());
        }
    }

    @Nested
    @DisplayName("residualQuery strips the out-of-corpus vocabulary")
    class ResidualQuery {

        @Test
        void removesTheMatchedMarkerAndCollapsesWhitespace() {
            assertEquals("down link down",
                    gate.residualQuery("pseudowire down link down"));
        }

        @Test
        void removalIsCaseInsensitiveAndWordBounded() {
            assertEquals("Status of the tunnel",
                    gate.residualQuery("Status of the PSEUDOWIRE tunnel"));
        }

        @Test
        void nullWhenNothingUsefulRemains() {
            // Stripping the marker leaves fewer than 8 characters.
            assertNull(gate.residualQuery("pseudowire"));
            assertNull(gate.residualQuery("a pseudowire"));
        }

        @Test
        void nullForBlockedQueries() {
            assertNull(gate.residualQuery("pfcp association recovery procedure"));
        }

        @Test
        void nullWhenNoMarkerMatchesAtAll() {
            assertNull(gate.residualQuery("pdu session establishment procedure"));
            assertNull(gate.residualQuery(null));
            assertNull(gate.residualQuery("   "));
        }
    }

    @Nested
    @DisplayName("load-time degradation")
    class LoadDegradation {

        @Test
        void missingFileDisablesTheGateWithoutThrowing() {
            ScopeGateService fresh = new ScopeGateService(new DefaultResourceLoader(),
                    props(dir.resolve("no-such-table.tsv").toUri().toString()));
            fresh.resolveAgainstIndex(Set.of());
            assertEquals(0, fresh.markerCount());
            assertEquals(0, fresh.activeMarkerCount());
            assertNull(fresh.outOfScopeReason("pfcp association recovery"));
            assertNull(fresh.coverageCaveat("pseudowire status"));
        }

        @Test
        void ioFailureMidReadDisablesTheGateWithoutThrowing() {
            ScopeGateService fresh = new ScopeGateService(
                    new FailingResourceSupport.FailingResourceLoader(),
                    props("stub:whatever.tsv"));
            fresh.resolveAgainstIndex(Set.of());
            assertEquals(0, fresh.markerCount());
            assertNull(fresh.outOfScopeReason("pfcp association recovery"));
        }
    }
}

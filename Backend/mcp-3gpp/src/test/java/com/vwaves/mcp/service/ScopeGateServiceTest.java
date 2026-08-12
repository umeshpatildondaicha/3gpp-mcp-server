package com.vwaves.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vwaves.mcp.config.RetrievalProperties;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * The scope gate turns a confidently-wrong answer into an honest refusal, so its
 * failure modes are asymmetric:
 *   - a MISSED out-of-scope query is the old behaviour (bad, but no worse)
 *   - a FALSE POSITIVE blocks a question the corpus can actually answer, turning
 *     a correct result into a refusal — the worst regression this server can have
 * Most of these tests therefore guard the false-positive direction.
 */
class ScopeGateServiceTest {

    private ScopeGateService gate;

    /** The real shipped table, resolved against an index that lacks 29.244/24.229/32.x. */
    @BeforeEach
    void setUp() {
        RetrievalProperties props = new RetrievalProperties(
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
                "classpath:retrieval/intents.tsv",
                "classpath:retrieval/intent-exemplars.tsv", 0.50);
        gate = new ScopeGateService(new DefaultResourceLoader(), props);
        // Index holds the 3GPP specs this corpus actually has, minus the gated ones.
        gate.resolveAgainstIndex(Set.of("23.501", "23.502", "23.228", "23.167", "23.401",
                "29.274", "29.500", "29.510", "28.552", "32.421", "36.331", "38.331"));
    }

    @Nested
    @DisplayName("fires on out-of-scope queries")
    class Fires {

        @Test
        void catchesPfcp() {
            String r = gate.outOfScopeReason("UPF restart and PFCP association recovery");
            assertNotNull(r, "PFCP question must be gated — 29.244 is not indexed");
            assertTrue(r.contains("29.244"), "must name the spec the user needs: " + r);
            assertTrue(r.contains("WebSearch"), "must tell the caller where to go instead");
        }

        @Test
        void catchesCharging() {
            assertNotNull(gate.outOfScopeReason("offline charging CDR generation at the SMF"));
        }

        @Test
        void catchesSipStageThree() {
            String r = gate.outOfScopeReason("SIP INVITE handling at the S-CSCF");
            assertNotNull(r);
            assertTrue(r.contains("24.229"));
        }

        @Test
        void reportsEachOwningSpecOnce() {
            // "pfcp" and "n4 session" both map to 29.244 — it should be listed once.
            String r = gate.outOfScopeReason("PFCP n4 session establishment");
            assertNotNull(r);
            assertEquals(r.indexOf("29.244"), r.lastIndexOf("29.244"),
                    "29.244 should appear exactly once: " + r);
        }
    }

    @Nested
    @DisplayName("does NOT fire on answerable queries")
    class DoesNotFire {

        @Test
        void allowsImsArchitecture() {
            // 23.228 IS indexed and owns IMS architecture — "s-cscf" is deliberately
            // not a marker, precisely so this keeps working.
            assertNull(gate.outOfScopeReason("IMS registration with P-CSCF and S-CSCF"));
        }

        @Test
        void allowsImsEmergency() {
            assertNull(gate.outOfScopeReason("IMS emergency session establishment"));
        }

        @Test
        void allowsGtpv2ControlPlane() {
            // 29.274 (GTPv2-C) IS indexed; only the GTP-U *extension header* is gated.
            assertNull(gate.outOfScopeReason("GTPv2-C create session request message"));
        }

        @Test
        void allowsOrdinaryFiveGQueries() {
            assertNull(gate.outOfScopeReason("PDU session establishment procedure"));
            assertNull(gate.outOfScopeReason("5G AKA authentication procedure"));
            assertNull(gate.outOfScopeReason("network slice selection NSSF S-NSSAI"));
        }

        @Test
        void allowsTraceQueries() {
            // 32.421 IS indexed — must not be swept up by the charging markers.
            assertNull(gate.outOfScopeReason("subscriber and equipment trace session activation"));
        }
    }

    @Nested
    @DisplayName("marker matching is word-boundary safe")
    class WordBoundaries {

        @Test
        void cdrDoesNotMatchInsideAnotherWord() {
            assertFalse(ScopeGateService.containsWord("cdrom drive failure", "cdr"));
            assertFalse(ScopeGateService.containsWord("the scdr value", "cdr"));
        }

        @Test
        void cdrMatchesAsAStandaloneToken() {
            assertTrue(ScopeGateService.containsWord("generate a cdr per session", "cdr"));
            assertTrue(ScopeGateService.containsWord("cdr", "cdr"));
        }

        @Test
        void hyphenIsAWordSeparator() {
            assertTrue(ScopeGateService.containsWord("gtp-u extension header format", "gtp-u extension header"));
        }
    }

    @Nested
    @DisplayName("gate disables itself once the spec is indexed")
    class SelfDisabling {

        @Test
        void markerStopsFiringWhenOwningSpecIsPresent() {
            gate.resolveAgainstIndex(Set.of("29.244"));   // pretend we ingested it
            assertNull(gate.outOfScopeReason("UPF restart and PFCP association recovery"),
                    "once 29.244 is indexed the PFCP markers must stop firing");
        }

        @Test
        void inertBeforeTheIndexIsResolved() {
            RetrievalProperties props = new RetrievalProperties(
                    5, 1, 4, 40, 10, 300, 4, 200,
                    0.15, 1.5, 60, 0.5, 0.5, 1.3, 1.25, 0.85, 10, 0.12, 0.25, 0.02,
                    2, 1500, 4, 700, 1000,
                    "classpath:retrieval/stop-words.txt",
                    "classpath:retrieval/and-term-subst.tsv",
                    "classpath:retrieval/non-3gpp-intent-terms.txt",
                    "classpath:retrieval/spec-ownership.tsv",
                0.30, 0.45, "classpath:retrieval/test-spec-prefixes.txt",
                "classpath:retrieval/procedure-layers.tsv",
                "classpath:retrieval/procedure-synonyms.tsv",
                "classpath:retrieval/series-catalog.tsv", 1.35,
                "classpath:retrieval/intents.tsv",
                "classpath:retrieval/intent-exemplars.tsv", 0.50);
            ScopeGateService fresh = new ScopeGateService(new DefaultResourceLoader(), props);
            // resolveAgainstIndex not yet called — must fail open, never block.
            assertNull(fresh.outOfScopeReason("UPF restart and PFCP association recovery"));
        }
    }

    @Test
    void handlesNullAndBlankQueries() {
        assertNull(gate.outOfScopeReason(null));
        assertNull(gate.outOfScopeReason("   "));
    }
}

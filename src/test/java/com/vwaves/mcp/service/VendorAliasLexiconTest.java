package com.vwaves.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vwaves.mcp.config.RetrievalProperties;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Guards the vendor-CM → 3GPP alias layer in and-term-subst.tsv.
 *
 * Bulk-CM audit payloads name attributes in the vendor MO model
 * ("siPeriodicity", "rootSeqIndex"); 3GPP spells the same IEs differently
 * ("si-Periodicity", "rootSequenceIndex"). Every vendor form has zero FTS hits
 * against the KB, so without the substitution the AND path cannot reach the
 * spec clause and the answer degrades to "the evidence does not contain any
 * LTE-RAN parameter definitions".
 *
 * These tests read the real classpath resources — they fail if an entry is
 * dropped, mis-cased, or annotated in a way the TSV loader mis-parses.
 */
class VendorAliasLexiconTest {

    private static LexiconService lexicon;
    private static KbDataService kb;

    @BeforeAll
    static void loadRealLexicons() {
        RetrievalProperties props = testProperties();
        lexicon = new LexiconService(new DefaultResourceLoader(), props);
        // rerankService / embeddingService are only touched by the search path,
        // not by term extraction or AND-query construction.
        kb = new KbDataService(null, props, lexicon, null);
    }

    @Nested
    @DisplayName("and-term-subst.tsv integrity")
    class TableIntegrity {

        @Test
        void everyValueIsCleanQueryText() {
            // The loader splits on the FIRST tab and keeps the remainder verbatim.
            // A trailing "# comment" on a data line would silently become part of
            // the canonical term and poison every AND query using that key.
            for (Map.Entry<String, String> e : lexicon.andTermSubst().entrySet()) {
                String v = e.getValue();
                assertFalse(v.contains("#"), e.getKey() + " → value carries a '#' comment: " + v);
                assertFalse(v.contains("\t"), e.getKey() + " → value carries a tab: " + v);
                assertEquals(v.trim(), v, e.getKey() + " → value has stray whitespace: " + v);
            }
        }

        @Test
        void everyKeyMatchesTheTokenCleaningRule() {
            // Keys are looked up after tok.replaceAll("[^A-Za-z0-9-]", "").toLowerCase().
            // A key containing anything else can never be hit.
            for (String k : lexicon.andTermSubst().keySet()) {
                assertTrue(k.matches("[a-z0-9-]+"), "unreachable key (never matches a cleaned token): " + k);
            }
        }

        @Test
        void preExistingEntriesSurvive() {
            assertEquals("mmtel", lexicon.andTermSubst().get("volte"));
            assertEquals("pdn", lexicon.andTermSubst().get("pgw"));
        }
    }

    @Nested
    @DisplayName("vendor CM attribute aliases")
    class VendorAliases {

        @Test
        void mapsSibAndPrachAttributes() {
            Map<String, String> m = lexicon.andTermSubst();
            assertEquals("si-periodicity", m.get("siperiodicity"));
            assertEquals("rootsequenceindex", m.get("rootseqindex"));
            assertEquals("prach-configindex", m.get("prachindex"));
            assertEquals("preambleinitialreceivedtargetpower", m.get("preambletargetpower"));
        }

        @Test
        void mapsRlcAndInactivityAttributes() {
            Map<String, String> m = lexicon.andTermSubst();
            assertEquals("maxretxthreshold", m.get("ulmaxretxthreshold"));
            assertEquals("maxretxthreshold", m.get("dlmaxretxthreshold"));
            assertEquals("t-pollretransmit", m.get("ultpollretransmit"));
            assertEquals("datainactivitytimer", m.get("uedatainactivitytimer"));
            assertEquals("datainactivitytimer", m.get("adaptiveuedatainactivitytimersupport"));
        }

        @Test
        void mapsMeasurementEventAttributes() {
            Map<String, String> m = lexicon.andTermSubst();
            assertEquals("threshold-rsrp", m.get("a1intera1thresholdrsrp"));
            assertEquals("threshold-rsrp", m.get("a2intersona2thresholdrsrp"));
            assertEquals("a3-offset", m.get("a3intera3offset"));
            assertEquals("a3-offset", m.get("a3intersona3offset"));
            assertEquals("triggerquantity", m.get("a3intertriggerquantity"));
        }

        @Test
        void leavesTokensThatAlreadyResolveAlone() {
            // These vendor names are also live 3GPP corpus tokens (tac 1008 chunks,
            // pci 745, earfcn 334, timealignmenttimer 107). Substituting them would
            // trade working recall for none.
            Map<String, String> m = lexicon.andTermSubst();
            for (String token : new String[] {
                    "tac", "pci", "earfcn", "severity",
                    "timealignmenttimer", "modificationperiodcoeff", "additionalspectrumemission" }) {
                assertFalse(m.containsKey(token), token + " already resolves in the corpus — do not alias it");
            }
        }
    }

    @Nested
    @DisplayName("end-to-end AND-query construction")
    class AndQueryPath {

        /** Mirrors what bm25TopK does for the primary AND attempt. */
        private String andQuery(String query, int limit) {
            return kb.buildAndFtsQuery(kb.applyAndTermSubst(query), limit, kb.andSubstTargets(query));
        }

        @Test
        void vendorAttributeReachesTheSpecIe() {
            // The failing shape from the CM audit payload.
            String and = andQuery("what is the allowed range for siPeriodicity", 4);
            assertTrue(and.contains("si-periodicity"),
                    "AND query must carry the 36.331 IE spelling, got: " + and);
            assertFalse(and.contains("siperiodicity"),
                    "vendor spelling must not survive into the AND query, got: " + and);
        }

        @Test
        void vendorNameDoesNotConsumeAnAndSlot() {
            // and-term-limit is 4. "Nokia" occurs in the corpus only in TR
            // contributor lists, so if it survives it burns a slot and forces the
            // whole query onto the OR fallback.
            String and = andQuery("Nokia LTE rootSeqIndex golden value", 4);
            assertFalse(and.contains("nokia"), "vendor name must be stop-worded, got: " + and);
            assertTrue(and.contains("rootsequenceindex"), "expected canonical IE, got: " + and);
        }

        @Test
        void longCanonicalTermSurvivesTruncation() {
            // Regression: "rootsequenceindex" is 17 chars, so termSpecificity puts it
            // in the last tier ("expansion noise") behind ordinary words like "value"
            // and "golden". Without pinning, the 4-slot limit dropped the one term the
            // question was actually about and the alias silently no-opped.
            String query = "Nokia LTE rootSeqIndex golden value 0 current value 26 compliant";
            assertTrue(andQuery(query, 4).contains("rootsequenceindex"),
                    "pinned canonical term must not be truncated, got: " + andQuery(query, 4));
            // Same for an even longer target with a busy query.
            String q2 = "endcPlmn true or false in SIB1 PLMN list";
            assertTrue(andQuery(q2, 4).contains("upperlayerindication"),
                    "pinned canonical term must not be truncated, got: " + andQuery(q2, 4));
        }

        @Test
        void pinnedFloorIsTheCanonicalTermsAlone() {
            // The final relaxation step. Audit filler ("allowed", "golden value")
            // never co-occurs with the IE in spec text — measured AND over the full
            // query = 0 chunks, AND over "si-periodicity" alone = 20.
            java.util.Set<String> pinned = kb.andSubstTargets("what is the allowed range for siPeriodicity");
            assertEquals(java.util.Set.of("si-periodicity"), pinned);
            String floor = kb.buildAndFtsQuery(String.join(" ", pinned), pinned.size(), pinned);
            assertEquals("{text series_desc}:\"si-periodicity\"", floor);
        }

        @Test
        void noAliasMeansNoBehaviourChange() {
            // Queries that never touch the alias table must produce an empty pinned
            // set, so the extra relaxation step and the OR-source append are no-ops.
            assertTrue(kb.andSubstTargets("PDU session establishment procedure in 5GS").isEmpty());
            assertTrue(kb.andSubstTargets("NR initial access random access procedure").isEmpty());
        }
    }

    /** Mirrors application.properties; only the three lexicon paths and andTermLimit matter here. */
    private static RetrievalProperties testProperties() {
        return new RetrievalProperties(
                3, 3, 3,              // maxDense/Hybrid/RerankPerSpec
                60, 4, 400, 3, 10,    // rerankCandidates, hybridCandidateMult, maxHybridCandidates,
                                      // binaryFilterOversample, maxOversample
                0.0, 0.05, 60, 0.85, 0.5, 1.0,  // scoring
                0.05, 0.9,            // release boost / discount
                10,                   // agreement top-N
                0.12, 0.25, 0.02,     // confidence margins
                2, 400,               // stub suppression
                4,                    // andTermLimit
                700, 800,             // study-report range
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
    }
}

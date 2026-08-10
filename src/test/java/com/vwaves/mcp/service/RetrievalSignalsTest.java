package com.vwaves.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vwaves.mcp.config.RetrievalProperties;
import com.vwaves.mcp.model.SearchHit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Unit tests for the pure helpers behind intent routing, release-aware ranking
 * and citation validation. These are the pieces that decide routing and scoring
 * without touching the DB, the embedding model or the reranker.
 */
class RetrievalSignalsTest {

    @Nested
    @DisplayName("releaseFromQuery")
    class ReleaseParsing {

        @Test
        void parsesHyphenatedForm() {
            assertEquals("Rel-18", KbDataService.releaseFromQuery("QoS flow in Rel-18"));
        }

        @Test
        void parsesSpelledOutForm() {
            assertEquals("Rel-17", KbDataService.releaseFromQuery("what changed in Release 17"));
        }

        @Test
        void parsesSpacedAndCaseInsensitiveForm() {
            assertEquals("Rel-15", KbDataService.releaseFromQuery("REL 15 NR features"));
        }

        @Test
        void returnsNullWhenNoReleaseNamed() {
            assertNull(KbDataService.releaseFromQuery("PDU session establishment procedure"));
        }

        @Test
        void doesNotMatchReleaseUsedAsAProcedureWord() {
            // "RRC connection release" is a procedure, not a release designator —
            // the digit requirement is what keeps these apart.
            assertNull(KbDataService.releaseFromQuery("RRC connection release procedure"));
        }

        @Test
        void handlesNullQuery() {
            assertNull(KbDataService.releaseFromQuery(null));
        }
    }

    @Nested
    @DisplayName("intent catalogue (loaded from intents.tsv, nothing compiled in)")
    class IntentRouting {

        private final IntentCatalogService catalog =
                new IntentCatalogService(new DefaultResourceLoader(), testProperties());

        @Test
        void detectsProcedure() {
            assertEquals("procedure",
                    catalog.classifyByKeyword("PDU session establishment procedure").id());
        }

        @Test
        void detectsTroubleshooting() {
            assertEquals("troubleshooting",
                    catalog.classifyByKeyword("what cause code is sent on registration reject").id());
        }

        @Test
        void detectsOutOfScope() {
            assertEquals("out_of_scope",
                    catalog.classifyByKeyword("Ericsson alarm catalog for cell down").id());
        }

        @Test
        void defaultsToClauseLookup() {
            assertEquals("clause_lookup",
                    catalog.classifyByKeyword("NRCellDU information object class attributes").id());
        }

        @Test
        void priorityOrderIsDataDriven() {
            // "Ericsson handover procedure pricing" matches both out_of_scope
            // (weak "ericsson" + strong "pricing") and procedure terms.
            // out_of_scope wins because intents.tsv gives it the lower priority
            // number — an ordering decision in config, not in code.
            // (The earlier phrasing used "nokia" + "cli command"; both were
            // removed from intents.tsv on 2026-07-31 when the Nokia/Cisco
            // CLI+YANG corpora were indexed and became in-scope.)
            assertEquals("out_of_scope",
                    catalog.classifyByKeyword("Ericsson handover procedure pricing").id());
        }

        @Test
        void defaultIsTheHighestPriorityValue() {
            assertEquals("clause_lookup", catalog.defaultIntent().id());
        }

        @Test
        void onlyVendorIntentIsSticky() {
            // Sticky means a keyword hit outranks the embedding classifier. Only
            // out_of_scope should claim that, or paraphrase recall regresses.
            for (IntentCatalogService.Intent i : catalog.intents()) {
                if (i.sticky()) {
                    assertEquals("out_of_scope", i.id(),
                            "unexpected sticky intent: " + i.id());
                }
            }
        }

        @Test
        void everyIntentCarriesAHint() {
            for (IntentCatalogService.Intent i : catalog.intents()) {
                assertTrue(i.hint() != null && !i.hint().isBlank(),
                        i.id() + " must carry a hint for the orchestrator");
            }
        }

        @Test
        void unknownIdFallsBackToDefault() {
            assertEquals(catalog.defaultIntent().id(), catalog.byId("nonsense").id());
            assertFalse(catalog.isKnown("nonsense"));
        }

        @Test
        void exemplarFileOnlyNamesDeclaredIntents() {
            // A typo'd intent id in intent-exemplars.tsv silently drops that
            // exemplar at startup, quietly degrading paraphrase recall.
            java.util.Set<String> declared = new java.util.HashSet<>();
            for (IntentCatalogService.Intent i : catalog.intents()) declared.add(i.id());
            try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new DefaultResourceLoader()
                            .getResource("classpath:retrieval/intent-exemplars.tsv")
                            .getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String t = line.strip();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    String[] p = t.split("\t", 2);
                    if (p.length != 2) continue;
                    assertTrue(declared.contains(p[0].strip()),
                            "exemplar names undeclared intent '" + p[0].strip() + "'");
                }
            } catch (java.io.IOException e) {
                throw new AssertionError("intent-exemplars.tsv unreadable", e);
            }
        }
    }

    @Nested
    @DisplayName("extractSpecIds")
    class CitationExtraction {

        @Test
        void findsPlainAndPrefixedIds() {
            Set<String> ids = ThreeGppToolService.extractSpecIds(
                    "The flow is in TS 23.502 and the message in 24.501.");
            assertEquals(Set.of("23.502", "24.501"), ids);
        }

        @Test
        void findsHyphenatedPartNumbers() {
            assertTrue(ThreeGppToolService.extractSpecIds("see TS 32.111-2 clause 4")
                    .contains("32.111-2"));
        }

        @Test
        void deduplicatesRepeatedCitations() {
            assertEquals(1, ThreeGppToolService.extractSpecIds(
                    "23.501 says X, and 23.501 also says Y").size());
        }

        @Test
        void ignoresProseWithoutSpecIds() {
            assertTrue(ThreeGppToolService.extractSpecIds(
                    "The UE registers with the network.").isEmpty());
        }

        @Test
        void doesNotMatchVersionNumbers() {
            // Guards the common false positive: a 2-digit.3-digit shape is required,
            // so "v19.2" and "clause 4.3.2" must not be read as spec IDs.
            assertTrue(ThreeGppToolService.extractSpecIds(
                    "version v19.2 clause 4.3.2 applies").isEmpty());
        }
    }

    @Nested
    @DisplayName("isStudyReportByTitle")
    class StudyReportByTitle {

        @Test
        void detectsStudyOn() {
            // 38.912 — doc_type='TS' in the DB and 912 is outside the 700-899
            // numeric convention, so the title is the only signal that catches it.
            assertTrue(KbDataService.isStudyReportByTitle(
                    "3GPP TS 38.912 — Study on New Radio (NR) access technology"));
        }

        @Test
        void detectsFeasibilityStudy() {
            assertTrue(KbDataService.isStudyReportByTitle(
                    "3GPP TS 23.937 — Feasibility Study of Mobility between 3GPP-WLAN"));
        }

        @Test
        void detectsTechnicalReport() {
            assertTrue(KbDataService.isStudyReportByTitle(
                    "3GPP TS 23.909 — Technical report on the Gateway Location Register"));
        }

        @Test
        void doesNotFlagNormativeSpecs() {
            assertFalse(KbDataService.isStudyReportByTitle(
                    "3GPP TS 36.331 — Evolved Universal Terrestrial Radio Access (E-UTRA); "
                            + "Radio Resource Control (RRC); Protocol specification"));
            assertFalse(KbDataService.isStudyReportByTitle(
                    "3GPP TS 38.321 — NR; Medium Access Control (MAC) protocol specification"));
        }

        @Test
        void toleratesPlaceholderAndNullTitles() {
            // Specs whose cover page did not survive extraction keep a placeholder
            // title; they must fall through to the doc_type / pattern signals.
            assertFalse(KbDataService.isStudyReportByTitle("3GPP TS 23.501"));
            assertFalse(KbDataService.isStudyReportByTitle(null));
        }
    }

    /** Mirrors application.properties; only the lexicon paths matter here. */
    private static RetrievalProperties testProperties() {
        return new RetrievalProperties(
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
    }

    @Nested
    @DisplayName("looksLikeDocument — reject pasted payloads, keep real questions")
    class DocumentGuard {

        @Test
        void rejectsRealBulkCmPayload() {
            // The case the guard exists for: a whole export averaged into one
            // embedding. Caught by size, which is what actually makes it unusable.
            String item = "{\"parameterName\":\"siPeriodicity\",\"goldenValue\":\"rf64\","
                    + "\"currentValue\":\"rf1024\",\"moHierarchy\":\"/x/y/z\"},";
            assertTrue(ThreeGppToolService.looksLikeDocument(
                    "{\"data\":{\"nonCompliancedata\":[" + item.repeat(20) + "]}}") != null);
        }

        @Test
        void rejectsOverlongInput() {
            assertTrue(ThreeGppToolService.looksLikeDocument("si-Periodicity ".repeat(200)) != null);
        }

        @Test
        void rejectsManyLinePaste() {
            assertTrue(ThreeGppToolService.looksLikeDocument("a\n".repeat(20)) != null);
        }

        @Test
        void rejectsLargeXmlDocument() {
            assertTrue(ThreeGppToolService.looksLikeDocument(
                    "<?xml version=\"1.0\"?><configData>"
                    + "<attr name=\"siPeriodicity\">rf1024</attr>".repeat(20)
                    + "</configData>") != null);
        }

        @Test
        void allowsAQuestionWrappedInJson() {
            // Regression: the guard used to test the first and last CHARACTER, so
            // any MCP client that wrapped its question in an envelope was refused
            // outright. This exact input returned an empty report instead of an
            // answer — 68 characters carrying one short question.
            assertNull(ThreeGppToolService.looksLikeDocument(
                    "{\"query\": \"VPN Pseudowire Down leading to Link Down\", \"max_results\": 5}"));
        }

        @Test
        void allowsAQuestionInAngleBrackets() {
            assertNull(ThreeGppToolService.looksLikeDocument(
                    "<query>RRC connection setup procedure in NR</query>"));
        }

        @Test
        void allowsEveryRealBenchmarkQuery() {
            // The guard must never fire on a legitimate question — a false positive
            // turns a working search into a refusal, which is worse than the noise
            // it prevents. These are representative of both benchmark suites.
            for (String ok : new String[] {
                    "PDU session establishment procedure",
                    "si-Periodicity SIB1NB SIB1NBSchedulingInfo",
                    "NRCellDU information object class attributes",
                    "walk me through how the UE comes back after losing the cell",
                    "5G registration and PDU session across NAS and NGAP",
                    "what cause code is sent on registration reject" }) {
                assertNull(ThreeGppToolService.looksLikeDocument(ok), "wrongly rejected: " + ok);
            }
        }

        @Test
        void allowsAMultiLineButShortQuestion() {
            // Two or three lines is a human typing, not a paste.
            assertNull(ThreeGppToolService.looksLikeDocument(
                    "PDU session establishment\nin 5G SA\nwith the SMF"));
        }
    }

    @Nested
    @DisplayName("applyFinalPerSpecCap — same-spec cap-drop reporting")
    class PerSpecCapDrops {

        private SearchHit hit(double score, String specId) {
            return new SearchHit(score, specId, "Rel-18", "title", "series", "snippet");
        }

        @Test
        void secondSameSpecHitIsDroppedAndReported() {
            List<SearchHit> sorted = List.of(hit(0.85, "38.331"), hit(0.79, "38.331"));
            KbDataService.HybridResult r = KbDataService.applyFinalPerSpecCap(sorted, 10, 1);

            assertEquals(1, r.hits().size());
            assertEquals(0.85, r.hits().get(0).score());
            KbDataService.CapDrop drop = r.capDrops().get("38.331");
            assertEquals(1, drop.droppedCount());
            assertEquals(0.79, drop.droppedTopScore());
        }

        @Test
        void differentSpecsUnderCapAreNotReportedAsDrops() {
            List<SearchHit> sorted = List.of(hit(0.85, "38.331"), hit(0.79, "24.501"));
            KbDataService.HybridResult r = KbDataService.applyFinalPerSpecCap(sorted, 10, 1);

            assertEquals(2, r.hits().size());
            assertTrue(r.capDrops().isEmpty());
        }

        @Test
        void multipleDropsForOneSpecAggregateCountAndKeepTheHighestScore() {
            List<SearchHit> sorted = List.of(
                    hit(0.90, "38.331"), hit(0.79, "38.331"), hit(0.60, "38.331"));
            KbDataService.HybridResult r = KbDataService.applyFinalPerSpecCap(sorted, 10, 1);

            KbDataService.CapDrop drop = r.capDrops().get("38.331");
            assertEquals(2, drop.droppedCount());
            // Highest-scoring DROPPED chunk, not the survivor — that's what tells the
            // caller how close the second-best chunk really was.
            assertEquals(0.79, drop.droppedTopScore());
        }

        @Test
        void topKTruncationStopsBeforeRecordingFurtherDrops() {
            // topK=1 means the loop breaks after the first hit — the second hit
            // (a different spec, would ordinarily survive the cap) is never even
            // evaluated, so it must not show up as a cap-drop either.
            List<SearchHit> sorted = List.of(hit(0.85, "38.331"), hit(0.79, "24.501"));
            KbDataService.HybridResult r = KbDataService.applyFinalPerSpecCap(sorted, 1, 1);

            assertEquals(1, r.hits().size());
            assertTrue(r.capDrops().isEmpty());
        }

        @Test
        void raisingMaxPerSpecKeepsTheSecondHitInstead() {
            List<SearchHit> sorted = List.of(hit(0.85, "38.331"), hit(0.79, "38.331"));
            KbDataService.HybridResult r = KbDataService.applyFinalPerSpecCap(sorted, 10, 3);

            assertEquals(2, r.hits().size());
            assertTrue(r.capDrops().isEmpty());
        }
    }
}

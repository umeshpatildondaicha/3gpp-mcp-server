package com.vwaves.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vwaves.mcp.config.RetrievalProperties;
import com.vwaves.mcp.model.ChunkMeta;
import com.vwaves.mcp.model.SearchHit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic unit tests for {@link KbDataService}: FTS query construction,
 * term extraction/substitution, extras-DB weighting, study-report detection,
 * confidence scoring, spec-mention matching and the binary-text guard.
 *
 * <p>No database is opened — the service is constructed with a mocked
 * {@link LexiconService} (so the lexicons are fully controlled by the test)
 * and null rerank/embedding collaborators, which none of the tested methods
 * dereference.
 */
class KbDataServiceLogicTest {

    private LexiconService lexicon;
    private KbDataService kb;

    @BeforeEach
    void setUp() {
        lexicon = mock(LexiconService.class);
        when(lexicon.stopWords()).thenReturn(Set.of(
                "the", "for", "what", "is", "in", "of", "a", "and", "to", "on"));
        when(lexicon.andTermSubst()).thenReturn(Map.of(
                "volte", "mmtel",
                "siperiodicity", "si-periodicity",
                "rootseqindex", "rootsequenceindex"));
        when(lexicon.non3gppIntentTerms()).thenReturn(Set.of("ip", "ospf", "ietf"));
        kb = new KbDataService(null, testProperties(), lexicon, null);
    }

    /** Same shape as the other service tests; scoring knobs are what matter here:
     *  extrasDbDiscount=0.5, extrasDbNeutralWeight=1.0, confidenceHighMargin=0.12,
     *  confidenceNoneTopScore=0.25, confidenceNoneMargin=0.02, studyRange=[700,800). */
    private static RetrievalProperties testProperties() {
        return new RetrievalProperties(
                3, 3, 3,
                60, 4, 400, 3, 10,
                0.0, 0.05, 60, 0.85, 0.5, 1.0,
                0.05, 0.9,
                10,
                0.12, 0.25, 0.02,
                2, 400,
                4,
                700, 800,
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

    // ── extractFtsTerms ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractFtsTerms")
    class ExtractFtsTerms {

        @Test
        void cleansLowercasesAndDropsStopWords() {
            assertThat(kb.extractFtsTerms("The 5G-RAN handover, for NSSAI!"))
                    .containsExactly("5g-ran", "handover", "nssai");
        }

        @Test
        void dropsSingleCharacterFragments() {
            // "N" cleans to a 1-char token, below the 2-char minimum.
            assertThat(kb.extractFtsTerms("N slicing")).containsExactly("slicing");
        }

        @Test
        void deduplicatesWhilePreservingFirstOccurrenceOrder() {
            assertThat(kb.extractFtsTerms("rach preamble rach msg3"))
                    .containsExactly("rach", "preamble", "msg3");
        }

        @Test
        void nullQueryYieldsEmptyList() {
            assertThat(kb.extractFtsTerms(null)).isEmpty();
        }
    }

    // ── buildAndFtsQuery / buildOrFtsQuery ───────────────────────────────────

    @Nested
    @DisplayName("FTS query construction")
    class FtsQueryConstruction {

        @Test
        void andQueryOrdersBySpecificity_digitThenHyphenThenShortThenLong() {
            // handover(short≤9) 5g(digit) s-nssai(hyphen) nssai(≤6) information(long)
            String q = kb.buildAndFtsQuery("handover 5g s-nssai nssai information", 4);
            assertThat(q).isEqualTo(
                    "{text series_desc}:\"5g\" AND {text series_desc}:\"s-nssai\" "
                    + "AND {text series_desc}:\"nssai\" AND {text series_desc}:\"handover\"");
        }

        @Test
        void andQuerySingleTermHasNoAndConnector() {
            assertThat(kb.buildAndFtsQuery("nssai", 4))
                    .isEqualTo("{text series_desc}:\"nssai\"");
        }

        @Test
        void andQueryEmptyForBlankOrStopWordOnlyQuery() {
            assertThat(kb.buildAndFtsQuery("", 4)).isEmpty();
            assertThat(kb.buildAndFtsQuery("the of and", 4)).isEmpty();
        }

        @Test
        void digitTermsExpandTheLimitSoNoDisambiguatorIsLost() {
            // 6 digit-bearing terms with baseLimit 4: all six survive (cap is 6),
            // and the non-digit term is what gets cut.
            String q = kb.buildAndFtsQuery("n1 n2 n3 n4 n6 5g handover", 4);
            for (String t : new String[] {"n1", "n2", "n3", "n4", "n6", "5g"}) {
                assertThat(q).contains("\"" + t + "\"");
            }
            assertThat(q).doesNotContain("handover");
        }

        @Test
        void pinnedTermsSortFirstAndSurviveTruncation() {
            // "rootsequenceindex" is 17 chars — lowest tier — and would lose its
            // slot at limit 2 without pinning.
            String q = kb.buildAndFtsQuery("information rootsequenceindex handover 5g",
                    2, Set.of("rootsequenceindex"));
            assertThat(q).isEqualTo(
                    "{text series_desc}:\"rootsequenceindex\" AND {text series_desc}:\"5g\"");
        }

        @Test
        void orQueryQuotesEveryTermAndJoinsWithOr() {
            assertThat(kb.buildOrFtsQuery("rach preamble"))
                    .isEqualTo("\"rach\" OR \"preamble\"");
        }

        @Test
        void orQueryEmptyForStopWordOnlyQuery() {
            assertThat(kb.buildOrFtsQuery("the for")).isEmpty();
        }
    }

    // ── applyAndTermSubst / andSubstTargets ──────────────────────────────────

    @Nested
    @DisplayName("AND-path term substitution")
    class TermSubstitution {

        @Test
        void mappedTokensAreRewrittenOthersPassThrough() {
            assertThat(kb.applyAndTermSubst("VoLTE call setup"))
                    .isEqualTo("mmtel call setup");
        }

        @Test
        void tokensAreCleanedBeforeLookup() {
            // Punctuation and case must not defeat the map key.
            assertThat(kb.applyAndTermSubst("(siPeriodicity)?"))
                    .isEqualTo("si-periodicity");
        }

        @Test
        void nullAndBlankQueriesYieldEmptyString() {
            assertThat(kb.applyAndTermSubst(null)).isEmpty();
            assertThat(kb.applyAndTermSubst("   ")).isEmpty();
        }

        @Test
        void substTargetsCollectCanonicalTermsOnly() {
            assertThat(kb.andSubstTargets("what range for siPeriodicity"))
                    .containsExactly("si-periodicity");
        }

        @Test
        void substTargetsEmptyWhenNoAliasFires() {
            assertThat(kb.andSubstTargets("PDU session establishment")).isEmpty();
            assertThat(kb.andSubstTargets(null)).isEmpty();
        }
    }

    // ── extrasDbWeightFor ────────────────────────────────────────────────────

    @Nested
    @DisplayName("extras-DB weighting")
    class ExtrasDbWeight {

        @Test
        void seriesFilterAlwaysNeutralises() {
            assertThat(kb.extrasDbWeightFor("anything at all", "38")).isEqualTo(1.0);
        }

        @Test
        void nullOrBlankQueryIsDiscounted() {
            assertThat(kb.extrasDbWeightFor(null, null)).isEqualTo(0.5);
            assertThat(kb.extrasDbWeightFor("  ", "")).isEqualTo(0.5);
        }

        @Test
        void longIntentTermMatchesBySubstring() {
            assertThat(kb.extrasDbWeightFor("OSPF area configuration", null))
                    .isEqualTo(1.0);
        }

        @Test
        void shortIntentTermMatchesOnWordBoundaryOnly() {
            // "ip" as a standalone word lifts the discount ...
            assertThat(kb.extrasDbWeightFor("ip routing table", null)).isEqualTo(1.0);
            // ... but "ip" inside "description" must NOT.
            assertThat(kb.extrasDbWeightFor("description of handover", null))
                    .isEqualTo(0.5);
        }

        @Test
        void pure3gppQueryKeepsTheDiscount() {
            assertThat(kb.extrasDbWeightFor("RRC connection setup", null)).isEqualTo(0.5);
        }
    }

    // ── confidenceOf ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("retrieval confidence tiers")
    class Confidence {

        private SearchHit hit(double score, String specId, int support) {
            return new SearchHit(score, specId, "Rel-18", "t", "NR", "s",
                    "0:x", "TS", support, 0);
        }

        @Test
        void emptyOrNullHitsAreLowWithZeroedFields() {
            KbDataService.RetrievalConfidence c = kb.confidenceOf(List.of());
            assertThat(c.level()).isEqualTo("low");
            assertThat(c.margin()).isZero();
            assertThat(c.topScore()).isZero();
            assertThat(c.distinctSpecs()).isZero();

            assertThat(kb.confidenceOf(null).level()).isEqualTo("low");
        }

        @Test
        void wideMarginIsHigh() {
            KbDataService.RetrievalConfidence c = kb.confidenceOf(List.of(
                    hit(0.90, "38.331", 1), hit(0.50, "23.501", 0)));
            assertThat(c.level()).isEqualTo("high");
            assertThat(c.margin()).isEqualTo(0.4);
            assertThat(c.topScore()).isEqualTo(0.9);
            assertThat(c.distinctSpecs()).isEqualTo(2);
        }

        @Test
        void singleHitUsesItsOwnScoreAsMargin() {
            KbDataService.RetrievalConfidence c =
                    kb.confidenceOf(List.of(hit(0.90, "38.331", 2)));
            assertThat(c.level()).isEqualTo("high");
            assertThat(c.margin()).isEqualTo(0.9);
        }

        @Test
        void narrowMarginWithBothRetrieversAgreeingIsMedium() {
            KbDataService.RetrievalConfidence c = kb.confidenceOf(List.of(
                    hit(0.50, "38.331", 2), hit(0.45, "23.501", 0)));
            assertThat(c.level()).isEqualTo("medium");
            assertThat(c.support()).isEqualTo(2);
        }

        @Test
        void narrowMarginWithoutAgreementIsLow() {
            KbDataService.RetrievalConfidence c = kb.confidenceOf(List.of(
                    hit(0.50, "38.331", 1), hit(0.45, "23.501", 2)));
            // support is read from the TOP hit only.
            assertThat(c.level()).isEqualTo("low");
        }

        @Test
        void everythingTiedAtTheFloorIsNone() {
            // The degenerate case the "none" tier exists for: low top score AND
            // near-zero margin together.
            KbDataService.RetrievalConfidence c = kb.confidenceOf(List.of(
                    hit(0.150, "38.331", 2), hit(0.149, "23.501", 2)));
            assertThat(c.level()).isEqualTo("none");
            assertThat(c.margin()).isEqualTo(0.001);
        }

        @Test
        void lowTopScoreAloneIsNotNone() {
            // 12 of 100 benchmark questions score top < 0.30 and are still right —
            // only the conjunction with a collapsed margin may demote to "none".
            KbDataService.RetrievalConfidence c = kb.confidenceOf(List.of(
                    hit(0.20, "38.331", 2), hit(0.05, "23.501", 0)));
            assertThat(c.level()).isEqualTo("high");
        }
    }

    // ── study-report detection ───────────────────────────────────────────────

    @Nested
    @DisplayName("study-report detection")
    class StudyReports {

        private ChunkMeta meta(String specId, String docType, String title) {
            return new ChunkMeta("0:c1", specId, "Rel-18", "23", "desc",
                    docType, title, 0);
        }

        @Test
        void docTypeTrWinsCaseInsensitively() {
            assertThat(kb.isStudyReport(meta("38.331", "TR", "any"))).isTrue();
            assertThat(kb.isStudyReport(meta("38.331", "tr", "any"))).isTrue();
            assertThat(kb.isStudyReportByDocType("TR", "38.331")).isTrue();
            assertThat(kb.isStudyReportByDocType("tr", "38.331")).isTrue();
        }

        @Test
        void numericRangeFallbackCatches700Series() {
            assertThat(kb.isStudyReportByDocType("TS", "23.799")).isTrue();
            assertThat(kb.isStudyReportByDocType(null, "36.750")).isTrue();
            // Hyphenated part numbers: range check uses the part before the dash.
            assertThat(kb.isStudyReportByDocType("TS", "23.700-28")).isTrue();
        }

        @Test
        void normativeSpecsOutsideTheRangeAreNotFlagged() {
            assertThat(kb.isStudyReportByDocType("TS", "38.331")).isFalse();
            assertThat(kb.isStudyReportByDocType("TS", "23.501")).isFalse();
            // 912 sits outside [700, 800) — only the title signal can catch it.
            assertThat(kb.isStudyReportByDocType("TS", "38.912")).isFalse();
        }

        @Test
        void malformedSpecIdsNeverFlag() {
            assertThat(kb.isStudyReportByDocType("TS", null)).isFalse();
            assertThat(kb.isStudyReportByDocType("TS", "RFC5880")).isFalse();
            assertThat(kb.isStudyReportByDocType("TS", "23.abc")).isFalse();
        }

        @Test
        void titleSignalCatchesStudiesMislabelledAsTs() {
            assertThat(kb.isStudyReport(meta("38.912", "TS",
                    "Study on New Radio access technology"))).isTrue();
            assertThat(kb.isStudyReport(meta("38.331", "TS",
                    "Radio Resource Control (RRC); Protocol specification"))).isFalse();
        }

        @Test
        void nullMetaIsNotAStudyReport() {
            assertThat(kb.isStudyReport(null)).isFalse();
        }
    }

    // ── queryMentionsSpec ────────────────────────────────────────────────────

    @Nested
    @DisplayName("queryMentionsSpec")
    class QueryMentionsSpec {

        @Test
        void matchesPlainDottedAndUndottedForms() {
            assertThat(KbDataService.queryMentionsSpec("what does 38.331 say", "38.331")).isTrue();
            assertThat(KbDataService.queryMentionsSpec("TS 38331 rrc", "38.331")).isTrue();
        }

        @Test
        void matchesWithSourcePrefixStripped() {
            // Query names "X.733", index stores "ITU-T-X.733".
            assertThat(KbDataService.queryMentionsSpec("X.733 alarm model", "ITU-T-X.733")).isTrue();
            assertThat(KbDataService.queryMentionsSpec("x733 severity", "ITU-T-X.733")).isTrue();
        }

        @Test
        void matchesRfcWithAndWithoutSpace() {
            assertThat(KbDataService.queryMentionsSpec("RFC 5880 bfd timers", "RFC5880")).isTrue();
            assertThat(KbDataService.queryMentionsSpec("rfc5880", "RFC5880")).isTrue();
        }

        @Test
        void doesNotMatchUnrelatedQueries() {
            assertThat(KbDataService.queryMentionsSpec("handover procedure", "38.331")).isFalse();
        }

        @Test
        void nullsAreSafe() {
            assertThat(KbDataService.queryMentionsSpec(null, "38.331")).isFalse();
            assertThat(KbDataService.queryMentionsSpec("38.331", null)).isFalse();
        }
    }

    // ── looksBinary ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("binary-text guard")
    class LooksBinary {

        @Test
        void nullAndBlankAreBinary() {
            assertThat(KbDataService.looksBinary(null)).isTrue();
            assertThat(KbDataService.looksBinary("   ")).isTrue();
        }

        @Test
        void ordinaryProseIsNot() {
            assertThat(KbDataService.looksBinary(
                    "The UE shall initiate the registration procedure.")).isFalse();
        }

        @Test
        void knownOoxmlAndEmfMarkersAreBinary() {
            assertThat(KbDataService.looksBinary("prefix EMF+ garbage")).isTrue();
            assertThat(KbDataService.looksBinary("<w:pPr><w:val=\"x\"/>")).isTrue();
        }

        @Test
        void highNonAsciiRatioIsBinary() {
            assertThat(KbDataService.looksBinary("ÿþ".repeat(50))).isTrue();
        }

        @Test
        void occasionalNonAsciiInProseIsFine() {
            assertThat(KbDataService.looksBinary(
                    "The N² interface carries NGAP signalling between gNB and AMF."))
                    .isFalse();
        }
    }
}

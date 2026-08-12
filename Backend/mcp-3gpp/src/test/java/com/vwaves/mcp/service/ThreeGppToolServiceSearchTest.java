package com.vwaves.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vwaves.mcp.model.SearchFilter;
import com.vwaves.mcp.model.SearchHit;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * search3gpp — the single-query text path: formatting, verbosity, guard rails,
 * scope gate, clamping and the exact-match cache. Everything heavy is mocked;
 * assertions are on the formatted reply and on what reached the collaborators.
 */
class ThreeGppToolServiceSearchTest {

    private final ThreeGppToolServiceTestHarness h = new ThreeGppToolServiceTestHarness();

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("happy path renders header, confidence, intent and the hit block")
    void happyPathTextOutput() throws SQLException {
        String out = h.service.search3gpp("NR RRC reconfiguration", null, null, null, null, null, null);

        assertThat(out)
                .contains("Search results for: \"NR RRC reconfiguration\"")
                .contains("(1 results, verbosity=normal)")
                .contains("Confidence: high (margin=0.150, top=0.95, specs=3, retrievers=2/2)")
                .contains("(ranking separation only — not evidence the owning spec is indexed)")
                .contains("Intent: HINT-LOOKUP")
                .contains("right spec ~88%")
                .contains("[1] 38.331 | Rel-18 | Score: 0.95")
                .contains("Title  : NR RRC protocol specification")
                .contains("Series : Radio access (38-series)")
                // selection returned blank -> falls back to the normalized snippet
                .contains("Key    : The UE shall apply the RRC configuration.")
                .contains("More   : call getSpecInfo(specId=\"38.331\")");
    }

    @Test
    @DisplayName("hits and inputs flow to hybridSearchDetailed with defaults (k=10, no perSpec)")
    void defaultsReachRetrieval() throws SQLException {
        h.service.search3gpp("NR RRC reconfiguration", null, null, null, null, null, null);

        ArgumentCaptor<SearchFilter> filter = ArgumentCaptor.forClass(SearchFilter.class);
        verify(h.kb).hybridSearchDetailed(eq("NR RRC reconfiguration"),
                eq("NR RRC reconfiguration"), any(float[].class), eq(10),
                filter.capture(), eq((Integer) null));
        assertThat(filter.getValue()).isEqualTo(new SearchFilter(null, null, null));
        verify(h.queryLogger).logQuery(eq("NR RRC reconfiguration"), eq(10),
                any(SearchFilter.class), eq(1.0), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    @DisplayName("question lead-in is stripped from the embedding query only")
    void questionPrefixStrippedForEmbedding() throws SQLException {
        h.service.search3gpp("what is the PDU session establishment procedure",
                null, null, null, null, null, null);

        verify(h.embedding).embed("the PDU session establishment procedure");
        // Retrieval still receives the caller's original wording.
        verify(h.kb).hybridSearchDetailed(
                eq("what is the PDU session establishment procedure"), anyString(),
                any(float[].class), anyInt(), any(SearchFilter.class), nullable(Integer.class));
    }

    @Test
    @DisplayName("topK and maxPerSpec are clamped to 50 and 5")
    void clampsTopKAndMaxPerSpec() throws SQLException {
        h.service.search3gpp("q1 clamps high", 999, null, null, null, null, 99);
        verify(h.kb).hybridSearchDetailed(eq("q1 clamps high"), anyString(), any(float[].class),
                eq(50), any(SearchFilter.class), eq(5));

        h.service.search3gpp("q2 clamps low", 0, null, null, null, null, 0);
        verify(h.kb).hybridSearchDetailed(eq("q2 clamps low"), anyString(), any(float[].class),
                eq(1), any(SearchFilter.class), eq(1));
    }

    // ── Verbosity ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("verbosity")
    class VerbosityVariants {

        @Test
        void briefUsesBriefSelectionAndLabelsKey() throws SQLException {
            when(h.rerank.selectRelevantSentences(anyString(), anyString(), any()))
                    .thenReturn("Only the key sentence.");
            String out = h.service.search3gpp("NR paging", null, null, null, null, "brief", null);

            assertThat(out)
                    .contains("verbosity=brief")
                    .contains("Key    : Only the key sentence.")
                    .doesNotContain("Excerpt:");
            verify(h.rerank).briefSelection();
            verify(h.rerank, never()).normalSelection();
        }

        @Test
        void fullReturnsRawExcerptWithoutSelectionOrMoreLine() throws SQLException {
            String out = h.service.search3gpp("NR paging", null, null, null, null, "full", null);

            assertThat(out)
                    .contains("verbosity=full")
                    .contains("Excerpt: The UE shall apply the RRC configuration.")
                    .doesNotContain("More   :");
            verify(h.rerank, never()).selectRelevantSentences(anyString(), anyString(), any());
        }

        @Test
        void unknownVerbosityFallsBackToNormal() throws SQLException {
            String out = h.service.search3gpp("NR paging", null, null, null, null, "shouty", null);
            assertThat(out).contains("verbosity=normal");
        }

        @Test
        void emptySnippetGetsThePlaceholderBody() throws SQLException {
            when(h.kb.hybridSearchDetailed(anyString(), anyString(), any(float[].class), anyInt(),
                    any(SearchFilter.class), nullable(Integer.class)))
                    .thenReturn(ThreeGppToolServiceTestHarness.resultOf(
                            ThreeGppToolServiceTestHarness.hit(0.9, "23.501", "Arch", "SA", "   ")));
            String out = h.service.search3gpp("empty snippet", null, null, null, null, null, null);
            assertThat(out)
                    .contains("(chunk has no extractable text — call getSpecInfo for full content)")
                    .doesNotContain("More   :");
        }
    }

    // ── Confidence notes / no-result path ────────────────────────────────────

    @Test
    @DisplayName("no results renders the fixed low-confidence block")
    void noResults() throws SQLException {
        when(h.kb.hybridSearchDetailed(anyString(), anyString(), any(float[].class), anyInt(),
                any(SearchFilter.class), nullable(Integer.class)))
                .thenReturn(new KbDataService.HybridResult(List.of(), Map.of()));

        String out = h.service.search3gpp("nothing here", null, null, null, null, null, null);
        assertThat(out)
                .contains("No results found for: \"nothing here\"")
                .contains("Confidence: low (margin=0.000, top=0.00, specs=0)")
                .contains("Intent: HINT-LOOKUP");
    }

    @Test
    @DisplayName("confidence levels none/medium/low pick their own guidance note")
    void confidenceNoteVariants() throws SQLException {
        when(h.kb.confidenceOf(any())).thenReturn(
                new KbDataService.RetrievalConfidence("none", 0.0, 0.05, 1, 0));
        assertThat(h.service.search3gpp("q none", null, null, null, null, null, null))
                .contains("no real match")
                .contains("Do not cite these");

        when(h.kb.confidenceOf(any())).thenReturn(
                new KbDataService.RetrievalConfidence("medium", 0.05, 0.7, 2, 2));
        assertThat(h.service.search3gpp("q medium", null, null, null, null, null, null))
                .contains("right ~81% of the time");

        when(h.kb.confidenceOf(any())).thenReturn(
                new KbDataService.RetrievalConfidence("low", 0.01, 0.4, 2, 1));
        assertThat(h.service.search3gpp("q low", null, null, null, null, null, null))
                .contains("right only ~27% of the time");
    }

    // ── Scope gate ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("out-of-scope query short-circuits before retrieval and is logged")
    void outOfScope() throws SQLException {
        when(h.scopeGate.outOfScopeReason("pseudowire redundancy"))
                .thenReturn("OUT OF SCOPE: RFC 8077 is not indexed.");

        String out = h.service.search3gpp("pseudowire redundancy", null, null, null, null, null, null);

        assertThat(out).isEqualTo("OUT OF SCOPE: RFC 8077 is not indexed.");
        verify(h.queryLogger).logQuery(eq("pseudowire redundancy"), eq(0),
                any(SearchFilter.class), eq(0.0), eq(0L), eq(List.of()));
        verify(h.kb, never()).hybridSearchDetailed(anyString(), anyString(), any(float[].class),
                anyInt(), any(SearchFilter.class), nullable(Integer.class));
    }

    @Test
    @DisplayName("coverage caveat is prepended and switches the confidence note")
    void coverageCaveat() throws SQLException {
        when(h.scopeGate.coverageCaveat("vccv verification")).thenReturn(
                "COVERAGE NOTE: the owning spec is not indexed.");

        String out = h.service.search3gpp("vccv verification", null, null, null, null, null, null);

        assertThat(out).startsWith("COVERAGE NOTE: the owning spec is not indexed.\n\n")
                .contains("the low score is EXPECTED here")
                .doesNotContain("right spec ~88%");
    }

    // ── Input guards ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("input errors")
    class InputErrors {

        @Test
        void blankQuery() throws SQLException {
            String out = h.service.search3gpp("   ", null, null, null, null, null, null);
            assertThat(out).startsWith("Query is empty.");
            verify(h.kb, never()).hybridSearchDetailed(anyString(), anyString(), any(float[].class),
                    anyInt(), any(SearchFilter.class), nullable(Integer.class));
        }

        @Test
        void overlongQueryRejectedWithGuidance() throws SQLException {
            String out = h.service.search3gpp("x".repeat(601), null, null, null, null, null, null);
            assertThat(out)
                    .startsWith("Query rejected: the query is 601 characters")
                    .contains("parse the document yourself")
                    .contains("lookupIeDefinition");
        }

        @Test
        void manyNewlinesRejected() throws SQLException {
            String out = h.service.search3gpp("line\n".repeat(10), null, null, null, null, null, null);
            assertThat(out).contains("the query spans many lines");
        }

        @Test
        void unknownSeriesFilterListsTheIndexedOnes() throws SQLException {
            String out = h.service.search3gpp("NR paging", null, "99", null, null, null, null);
            assertThat(out)
                    .startsWith("Series '99' is not in the indexed knowledge base.")
                    .contains("23, 24, 36, 38")
                    .contains("listSeries");
            verify(h.kb, never()).hybridSearchDetailed(anyString(), anyString(), any(float[].class),
                    anyInt(), any(SearchFilter.class), nullable(Integer.class));
        }
    }

    // ── Cache ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("identical repeat call is served from the cache — one retrieval only")
    void exactMatchCache() throws SQLException {
        String first  = h.service.search3gpp("NB-IoT paging", 5, "36", "Rel-18", "TS", "brief", 2);
        String second = h.service.search3gpp("NB-IoT paging", 5, "36", "Rel-18", "TS", "brief", 2);

        assertThat(second).isEqualTo(first);
        verify(h.kb, times(1)).hybridSearchDetailed(anyString(), anyString(), any(float[].class),
                anyInt(), any(SearchFilter.class), nullable(Integer.class));
    }

    @Test
    @DisplayName("changing any keyed input misses the cache")
    void cacheKeyIncludesInputs() throws SQLException {
        h.service.search3gpp("NB-IoT paging", 5, null, null, null, null, null);
        h.service.search3gpp("NB-IoT paging", 6, null, null, null, null, null);
        h.service.search3gpp("NB-IoT paging", 5, null, null, null, "full", null);

        verify(h.kb, times(3)).hybridSearchDetailed(anyString(), anyString(), any(float[].class),
                anyInt(), any(SearchFilter.class), nullable(Integer.class));
    }

    // ── Adjacent context and cap-drop notes ──────────────────────────────────

    @Test
    @DisplayName("adjacent-context previews render as Before/After lines")
    void adjacentContextPreviews() throws SQLException {
        when(h.kb.adjacentContext(any(SearchHit.class), anyInt()))
                .thenReturn(new KbDataService.AdjacentContext("tail of prior chunk", "head of next"));

        String out = h.service.search3gpp("NR paging occasion", null, null, null, null, null, null);
        assertThat(out)
                .contains("Before : …tail of prior chunk")
                .contains("After  : head of next…");
    }

    @Test
    @DisplayName("a close same-spec cap drop earns the maxPerSpec note; a distant one does not")
    void capDropNote() throws SQLException {
        SearchHit top = ThreeGppToolServiceTestHarness.defaultHit(); // score 0.95
        when(h.kb.hybridSearchDetailed(anyString(), anyString(), any(float[].class), anyInt(),
                any(SearchFilter.class), nullable(Integer.class)))
                .thenReturn(new KbDataService.HybridResult(List.of(top),
                        Map.of("38.331", new KbDataService.CapDrop("38.331", 2, 0.9))));
        String close = h.service.search3gpp("close drop", null, null, null, null, null, null);
        assertThat(close).contains("this spec had 2 more chunk(s) scoring close (0.9)");

        when(h.kb.hybridSearchDetailed(anyString(), anyString(), any(float[].class), anyInt(),
                any(SearchFilter.class), nullable(Integer.class)))
                .thenReturn(new KbDataService.HybridResult(List.of(top),
                        Map.of("38.331", new KbDataService.CapDrop("38.331", 2, 0.5))));
        String distant = h.service.search3gpp("distant drop", null, null, null, null, null, null);
        assertThat(distant).doesNotContain("more chunk(s) scoring close");
    }

    // ── Score cut (via the pipeline and directly) ────────────────────────────

    @Test
    @DisplayName("hits far below the top score are cut from the reply")
    void relativeScoreCutInPipeline() throws SQLException {
        when(h.kb.hybridSearchDetailed(anyString(), anyString(), any(float[].class), anyInt(),
                any(SearchFilter.class), nullable(Integer.class)))
                .thenReturn(ThreeGppToolServiceTestHarness.resultOf(
                        ThreeGppToolServiceTestHarness.hit(0.9, "38.331", "T1", "S1", "keeper"),
                        ThreeGppToolServiceTestHarness.hit(0.05, "23.501", "T2", "S2", "noise")));

        String out = h.service.search3gpp("one real answer", null, null, null, null, null, null);
        assertThat(out)
                .contains("(1 results")
                .contains("38.331")
                .doesNotContain("23.501");
    }

    @Nested
    @DisplayName("applyScoreCut (static)")
    class ApplyScoreCut {

        @Test
        void keepsEverythingAboveTheRelativeBar() {
            List<SearchHit> hits = List.of(
                    ThreeGppToolServiceTestHarness.hit(0.8, "38.331", "a", "s", "x"),
                    ThreeGppToolServiceTestHarness.hit(0.4, "23.501", "b", "s", "y"));
            assertThat(ThreeGppToolService.applyScoreCut(hits)).hasSize(2);
        }

        @Test
        void dropsTheWholeListWhenEvenTheTopIsUnderTheFloor() {
            List<SearchHit> hits = List.of(
                    ThreeGppToolServiceTestHarness.hit(0.05, "38.331", "a", "s", "x"),
                    ThreeGppToolServiceTestHarness.hit(0.04, "23.501", "b", "s", "y"));
            assertThat(ThreeGppToolService.applyScoreCut(hits)).isEmpty();
        }

        @Test
        void emptyInputStaysEmpty() {
            assertThat(ThreeGppToolService.applyScoreCut(List.of())).isEmpty();
        }
    }
}

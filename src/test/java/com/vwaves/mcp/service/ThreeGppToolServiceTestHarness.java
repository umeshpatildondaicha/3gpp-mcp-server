package com.vwaves.mcp.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vwaves.mcp.config.RetrievalProperties;
import com.vwaves.mcp.model.SearchFilter;
import com.vwaves.mcp.model.SearchHit;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared fixture for the ThreeGppToolService unit tests: the service constructed
 * directly with Mockito mocks of every collaborator, plus benign default stubs so
 * each test only overrides the behaviour it is actually about.
 *
 * <p>Not a test class itself — it carries no {@code @Test} methods.
 */
final class ThreeGppToolServiceTestHarness {

    final EmbeddingService embedding = mock(EmbeddingService.class);
    final KbDataService kb = mock(KbDataService.class);
    final GlossaryService glossary = mock(GlossaryService.class);
    final QueryLogger queryLogger = mock(QueryLogger.class);
    final RerankService rerank = mock(RerankService.class);
    final ScopeGateService scopeGate = mock(ScopeGateService.class);
    final IntentClassifierService intentClassifier = mock(IntentClassifierService.class);
    final LexiconService lexicon = mock(LexiconService.class);
    final ProcedureLayerService procedureConfig = mock(ProcedureLayerService.class);
    final SeriesCatalogService seriesCatalog = mock(SeriesCatalogService.class);
    final RetrievalProperties props = defaultProps();

    final ThreeGppToolService service;

    ThreeGppToolServiceTestHarness() {
        try {
            stubDefaults();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        service = new ThreeGppToolService(
                embedding, kb, glossary, queryLogger, rerank, scopeGate,
                intentClassifier, lexicon, procedureConfig, seriesCatalog, props);
    }

    private void stubDefaults() throws SQLException {
        when(glossary.expand(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(scopeGate.outOfScopeReason(anyString())).thenReturn(null);
        when(scopeGate.coverageCaveat(anyString())).thenReturn(null);
        when(intentClassifier.classify(anyString())).thenReturn(new IntentClassifierService.Result(
                new IntentCatalogService.Intent("lookup", 1, false, "HINT-LOOKUP"),
                "keyword", 0.9));
        when(embedding.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(kb.extrasDbWeightFor(anyString(), nullable(String.class))).thenReturn(1.0);
        when(kb.indexedSeries()).thenReturn(Set.of("23", "24", "36", "38"));
        when(kb.confidenceOf(any())).thenReturn(
                new KbDataService.RetrievalConfidence("high", 0.150, 0.95, 3, 2));
        when(kb.adjacentContext(any(SearchHit.class), anyInt()))
                .thenReturn(new KbDataService.AdjacentContext(null, null));
        when(kb.hybridSearchDetailed(anyString(), anyString(), any(float[].class), anyInt(),
                any(SearchFilter.class), nullable(Integer.class)))
                .thenReturn(new KbDataService.HybridResult(List.of(defaultHit()), Map.of()));
        RerankService.SelectionConfig cfg =
                new RerankService.SelectionConfig(3, 0.2, 0.1, 0.7, 1, 800);
        when(rerank.briefSelection()).thenReturn(cfg);
        when(rerank.normalSelection()).thenReturn(cfg);
        // Blank means "selection found nothing", which makes the formatter fall
        // back to the normalized snippet — the least surprising default.
        when(rerank.selectRelevantSentences(anyString(), anyString(),
                any(RerankService.SelectionConfig.class))).thenReturn("");
        when(lexicon.isTestSpec(anyString())).thenReturn(false);
        when(procedureConfig.expandProcedure(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    static SearchHit defaultHit() {
        return hit(0.95, "38.331", "NR RRC protocol specification",
                "Radio access (38-series)", "The UE shall apply the RRC configuration.");
    }

    static SearchHit hit(double score, String specId, String title, String seriesDesc,
                         String snippet) {
        return new SearchHit(score, specId, "Rel-18", title, seriesDesc, snippet,
                "main:" + specId, "TS", 2, 4);
    }

    /** A single-hit retrieval result with no cap drops. */
    static KbDataService.HybridResult resultOf(SearchHit... hits) {
        return new KbDataService.HybridResult(List.of(hits), Map.of());
    }

    /** Real record instance: only the fields ThreeGppToolService reads matter here
     *  (confidenceHighMargin, procedureLayerFloor, procedureLayerRelativeFloor,
     *  procedurePreferBoost); the rest are plausible defaults. */
    static RetrievalProperties defaultProps() {
        return new RetrievalProperties(
                3, 1, 1,                     // per-spec caps
                50, 10, 100, 4, 200,         // pool sizes
                0.0, 1.5, 60, 0.5, 0.5, 1.0, // scoring
                1.1, 0.9,                    // release-aware ranking
                10,                          // agreementTopN
                0.12, 0.30, 0.05,            // confidence gate
                2, 200,                      // stub suppression
                6,                           // andTermLimit
                700, 800,                    // study-report range
                "", "", "", "",              // resource paths
                0.15, 0.30,                  // procedure layer floors
                "", "", "", "",              // more resource paths
                1.15,                        // procedurePreferBoost
                "", "",                      // intent paths
                0.5);                        // intentMinSimilarity
    }
}

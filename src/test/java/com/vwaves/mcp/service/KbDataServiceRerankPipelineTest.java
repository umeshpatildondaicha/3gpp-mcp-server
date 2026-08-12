package com.vwaves.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.vwaves.mcp.model.SearchFilter;
import com.vwaves.mcp.model.SearchHit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The reranker-active branch of {@link KbDataService#hybridSearchDetailed}:
 * candidate-pool collection with the looser per-spec cap, the passthrough
 * rerank call, post-rerank score adjustment (study-report discount,
 * query-implied release boost/discount) and the final per-spec cap with its
 * {@link KbDataService.CapDrop} report.
 *
 * <p>Same engineered corpus as KbDataServiceHybridSearchTest; the
 * {@link RerankService} mock is "ready" and returns its input unchanged, so
 * scores entering rerankAndCap are the hand-computable normalised RRF values.
 */
class KbDataServiceRerankPipelineTest {

    @TempDir
    Path tmp;

    private Path dbFile;
    private RerankService rerank;
    private KbDataService kb;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = tmp.resolve("rerank-corpus.db");
        Files.deleteIfExists(dbFile);
        try (Connection conn = KbSearchTestSupport.open(dbFile)) {
            KbDataServiceHybridSearchTest.seedCorpus(conn);
        }
        rerank = KbSearchTestSupport.rerankPassthrough();
        kb = new KbDataService(rerank,
                KbSearchTestSupport.defaultProps(),
                KbSearchTestSupport.lexicon(),
                KbSearchTestSupport.embedding());
        kb.init(List.of(dbFile), new StartupState());
    }

    private static float[] axis(int i) {
        float[] v = new float[4];
        v[i] = 1f;
        return v;
    }

    @Test
    @DisplayName("final per-spec cap reports what it cut: two extra 38.331 chunks")
    void capDropsReportSameSpecChunksTheCapHid() throws Exception {
        KbDataService.HybridResult result = kb.hybridSearchDetailed("rach preamble",
                "rach preamble", axis(0), 5, SearchFilter.NONE, null);

        List<SearchHit> hits = result.hits();
        assertThat(hits.get(0).specId()).isEqualTo("38.331");
        assertThat(hits.get(0).score()).isGreaterThanOrEqualTo(0.98);
        // Cap 1 → all returned specs distinct.
        assertThat(hits.stream().map(SearchHit::specId).distinct()).hasSize(hits.size());

        // a2 and a3 cleared the floor and the reranker; only the cap cut them.
        KbDataService.CapDrop drop = result.capDrops().get("38.331");
        assertThat(drop).isNotNull();
        assertThat(drop.droppedCount()).isEqualTo(2);
        // The best dropped chunk (a2) sat just below a1's near-perfect score.
        assertThat(drop.droppedTopScore()).isBetween(0.9, 1.0);

        verify(rerank).rerank(eq("rach preamble"), anyList(), anyInt());
    }

    @Test
    @DisplayName("maxPerSpecOverride raises the final cap so a spec keeps three chunks")
    void overrideRaisesFinalCapAfterRerank() throws Exception {
        KbDataService.HybridResult result = kb.hybridSearchDetailed("rach preamble",
                "rach preamble", axis(0), 5, SearchFilter.NONE, 3);

        assertThat(result.hits().stream().filter(h -> h.specId().equals("38.331")))
                .hasSize(3);
        assertThat(result.capDrops()).doesNotContainKey("38.331");
    }

    @Test
    @DisplayName("release named in the query boosts matching hits post-rerank")
    void queryImpliedReleaseBoostsMatchingRelease() throws Exception {
        // c2 (36.331, Rel-15) is rank 1 on both retrievers → pool score 1.0.
        // "Rel-15" in the query text multiplies it by releaseMatchBoost 1.2.
        List<SearchHit> hits = kb.hybridSearch("handover Rel-15", "handover Rel-15",
                new float[]{0f, 0f, 1f, 0.3f}, 3, SearchFilter.NONE);

        assertThat(hits.get(0).specId()).isEqualTo("36.331");
        assertThat(hits.get(0).release()).isEqualTo("Rel-15");
        assertThat(hits.get(0).score()).isCloseTo(1.2, within(1e-6));
    }

    @Test
    @DisplayName("release named in the query discounts mismatching hits post-rerank")
    void queryImpliedReleaseDiscountsMismatch() throws Exception {
        // Same top hit, but the query asks for Rel-18: 1.0 × 0.8.
        List<SearchHit> hits = kb.hybridSearch("handover Rel-18", "handover Rel-18",
                new float[]{0f, 0f, 1f, 0.3f}, 3, SearchFilter.NONE);

        assertThat(hits.get(0).specId()).isEqualTo("36.331");
        assertThat(hits.get(0).score()).isCloseTo(0.8, within(1e-6));
    }

    @Test
    @DisplayName("an explicit release filter disables the implied-release boost")
    void explicitReleaseFilterSuppressesImpliedBoost() throws Exception {
        List<SearchHit> hits = kb.hybridSearch("handover Rel-15", "handover Rel-15",
                new float[]{0f, 0f, 1f, 0.3f}, 3,
                new SearchFilter(null, "Rel-15", null));

        assertThat(hits.get(0).specId()).isEqualTo("36.331");
        // Every surviving hit already matches the filter → no boost applied.
        assertThat(hits.get(0).score()).isCloseTo(1.0, within(1e-6));
    }

    @Test
    @DisplayName("study-report discount is re-applied to reranker output")
    void studyDiscountSurvivesReranking() throws Exception {
        List<SearchHit> hits = kb.hybridSearch("network slicing s-nssai slice",
                "network slicing s-nssai slice", axis(1), 5, SearchFilter.NONE);

        assertThat(hits.get(0).specId()).isEqualTo("23.501");
        SearchHit tr = hits.stream().filter(h -> h.specId().equals("23.799"))
                .findFirst().orElseThrow();
        assertThat(hits.get(0).score()).isGreaterThan(tr.score());
    }
}

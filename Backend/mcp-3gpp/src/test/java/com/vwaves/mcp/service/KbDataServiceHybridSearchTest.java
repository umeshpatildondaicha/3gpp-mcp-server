package com.vwaves.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
 * End-to-end retrieval-pipeline tests for {@link KbDataService#hybridSearch}
 * against a hand-built temp SQLite corpus with FTS5 and real 4-dim embeddings.
 *
 * <p>The corpus is engineered so every assertion is decidable by hand:
 * <ul>
 *   <li>38.331 chunks embed along axis 1 and are the only prose containing
 *       "rach preamble" — a query with that text and vector (1,0,0,0) must win
 *       on BOTH retrievers (retrieverSupport 2).</li>
 *   <li>23.501 (TS) and 23.799 (TR) share one identical slicing chunk text and
 *       an identical axis-2 embedding; only the study-report discount separates
 *       them, so ordering proves the discount was applied.</li>
 *   <li>36.331 chunks embed along axis 3 and carry the mmtel/handover text used
 *       by the AND-relaxation, pinned-alias and OR-fallback paths.</li>
 *   <li>A one-chunk stub spec (X.700) and a binary-marker chunk (38.901) verify
 *       stub suppression and the looksBinary drop inside the fused-hit path.</li>
 * </ul>
 *
 * <p>coOccurrenceBoost is set to 1.0 (a no-op multiplier) so normalised RRF
 * scores stay at or below the 1.0 cap and relative ordering is exactly the
 * hand-computed one. The reranker mock reports not-ready: this file covers the
 * pure RRF branch; the reranker branch lives in KbDataServiceRerankPipelineTest.
 */
class KbDataServiceHybridSearchTest {

    private static final String SLICING_TEXT =
            "Network slicing uses the s-nssai to select a network slice instance "
                    + "for the session established by the ue in the visited network.";

    @TempDir
    Path tmp;

    private Path dbFile;
    private KbDataService kb;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = tmp.resolve("hybrid-corpus.db");
        Files.deleteIfExists(dbFile);
        try (Connection conn = KbSearchTestSupport.open(dbFile)) {
            seedCorpus(conn);
        }
        kb = new KbDataService(KbSearchTestSupport.rerankOff(),
                KbSearchTestSupport.defaultProps(),
                KbSearchTestSupport.lexicon(),
                KbSearchTestSupport.embedding());
        kb.init(List.of(dbFile), new StartupState());
    }

    /** Insertion order fixes every dense/BM25 tie-break (stable sorts + rowid). */
    static void seedCorpus(Connection conn) throws Exception {
        KbSearchTestSupport.createSchema(conn);
        // 23.799 — TR twin of the 23.501 slicing chunk. Inserted FIRST so it wins
        // every tie against 23.501 and only the study discount can demote it.
        KbSearchTestSupport.insertChunkWithVector(conn, "t1", "23.799", "Rel-18", "23",
                "SA2 architecture studies", "TR", "Study on network slicing", 0, 1,
                SLICING_TEXT, 0f, 1f, 0f, 0f);
        // 23.501 — the canonical TS.
        KbSearchTestSupport.insertChunkWithVector(conn, "b1", "23.501", "Rel-17", "23",
                "5G system architecture", "TS", "System architecture for the 5G System", 0, 2,
                SLICING_TEXT, 0f, 1f, 0f, 0f);
        KbSearchTestSupport.insertChunkWithVector(conn, "b2", "23.501", "Rel-17", "23",
                "5G system architecture", "TS", "System architecture for the 5G System", 1, 2,
                "The amf selects the smf for the requested dnn and slice information "
                        + "during registration procedures.", 0.2f, 1f, 0f, 0f);
        // 38.331 — axis-1 cluster; only chunks containing "rach preamble".
        KbSearchTestSupport.insertChunkWithVector(conn, "a1", "38.331", "Rel-18", "38",
                "NR; Radio Resource Control (RRC)", "TS", "NR RRC protocol specification", 0, 3,
                "The rach preamble transmission on msg1 uses the configured prach "
                        + "resources and power settings.", 1f, 0f, 0f, 0f);
        KbSearchTestSupport.insertChunkWithVector(conn, "a2", "38.331", "Rel-18", "38",
                "NR; Radio Resource Control (RRC)", "TS", "NR RRC protocol specification", 1, 3,
                "Power ramping applies for each rach preamble retransmission attempt "
                        + "until the counter expires.", 1f, 0.2f, 0f, 0f);
        KbSearchTestSupport.insertChunkWithVector(conn, "a3", "38.331", "Rel-18", "38",
                "NR; Radio Resource Control (RRC)", "TS", "NR RRC protocol specification", 2, 3,
                "The rrc reconfiguration message carries measurement configuration "
                        + "settings that concern the serving cell objects.", 0.8f, 0.6f, 0f, 0f);
        // 36.331 — axis-3 cluster; mmtel / handover text.
        KbSearchTestSupport.insertChunkWithVector(conn, "c1", "36.331", "Rel-15", "36",
                "LTE; Radio Resource Control", "TS", "E-UTRA RRC protocol specification", 0, 2,
                "The mmtel ims session uses sip signalling during call setup "
                        + "and teardown procedures.", 0f, 0f, 1f, 0f);
        KbSearchTestSupport.insertChunkWithVector(conn, "c2", "36.331", "Rel-15", "36",
                "LTE; Radio Resource Control", "TS", "E-UTRA RRC protocol specification", 1, 2,
                "The handover command includes mobility control information about "
                        + "the target cell configuration.", 0f, 0f, 1f, 0.3f);
        // X.700 — one short chunk: a stub spec, suppressed unless named.
        KbSearchTestSupport.insertChunkWithVector(conn, "s1", "X.700", "Rel-16", "X",
                "ITU-T management", "TS", "Management framework", 0, 1,
                "Stub spec.", 0f, 0f, 0f, 1f);
        // 38.901 — matches "rach preamble" on both retrievers but its text is
        // binary garbage (EMF+ marker): must be dropped inside toFusedHit.
        KbSearchTestSupport.insertChunkWithVector(conn, "z1", "38.901", "Rel-18", "38",
                "NR; channel model", "TS", "Channel model", 0, 1,
                "EMF+ rach preamble  binary payload garbage",
                1f, 0f, 0f, 0f);
        KbSearchTestSupport.insertMeta(conn, "embed_model", "test-model");
        KbSearchTestSupport.insertMeta(conn, "embed_dim", "4");
    }

    private static float[] axis(int i) {
        float[] v = new float[4];
        v[i] = 1f;
        return v;
    }

    @Test
    @DisplayName("query engineered for both retrievers wins with support 2 and a top score")
    void denseAndBm25AgreeOnEngineeredWinner() throws Exception {
        List<SearchHit> hits = kb.hybridSearch("rach preamble", "rach preamble",
                axis(0), 3, SearchFilter.NONE);

        assertThat(hits).isNotEmpty();
        SearchHit top = hits.get(0);
        assertThat(top.specId()).isEqualTo("38.331");
        assertThat(top.snippet()).contains("rach preamble transmission on msg1");
        assertThat(top.retrieverSupport()).isEqualTo(2);
        assertThat(top.score()).isGreaterThanOrEqualTo(0.98);
        assertThat(top.chunkId()).startsWith("0:");
        // The binary 38.901 chunk matched both retrievers too — it must be gone.
        assertThat(hits).noneMatch(h -> h.specId().equals("38.901"));
    }

    @Test
    @DisplayName("per-spec diversity cap: one chunk per spec by default, three with override")
    void perSpecCapDefaultOneVsOverride() throws Exception {
        List<SearchHit> capped = kb.hybridSearch("rach preamble", "rach preamble",
                axis(0), 5, SearchFilter.NONE);
        assertThat(capped.stream().filter(h -> h.specId().equals("38.331"))).hasSize(1);

        List<SearchHit> loose = kb.hybridSearch("rach preamble", "rach preamble",
                axis(0), 5, SearchFilter.NONE, 3);
        assertThat(loose.stream().filter(h -> h.specId().equals("38.331"))).hasSize(3);
    }

    @Test
    @DisplayName("series filter restricts both retriever legs")
    void seriesFilterRestrictsResults() throws Exception {
        List<SearchHit> hits = kb.hybridSearch("handover command", "handover command",
                new float[]{0f, 0f, 1f, 0.3f}, 5, SearchFilter.ofSeries("36"));

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).specId()).isEqualTo("36.331");
        assertThat(hits.get(0).snippet()).contains("handover command");
    }

    @Test
    @DisplayName("release filter keeps only matching-release chunks")
    void releaseFilterRestrictsResults() throws Exception {
        List<SearchHit> hits = kb.hybridSearch("network slicing s-nssai slice",
                "network slicing s-nssai slice", axis(1), 5,
                new SearchFilter(null, "Rel-17", null));

        assertThat(hits).isNotEmpty()
                        .allMatch(h -> h.release().equals("Rel-17"));
        assertThat(hits.get(0).specId()).isEqualTo("23.501");
    }

    @Test
    @DisplayName("docType filter TR returns only the study report")
    void docTypeFilterRestrictsResults() throws Exception {
        List<SearchHit> hits = kb.hybridSearch("network slicing s-nssai slice",
                "network slicing s-nssai slice", axis(1), 5,
                new SearchFilter(null, null, "TR"));

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).specId()).isEqualTo("23.799");
        assertThat(hits.get(0).docType()).isEqualTo("TR");
    }

    @Test
    @DisplayName("study-report discount demotes the TR twin below the canonical TS")
    void studyReportDiscountDemotesTr() throws Exception {
        List<SearchHit> hits = kb.hybridSearch("network slicing s-nssai slice",
                "network slicing s-nssai slice", axis(1), 5, SearchFilter.NONE);

        assertThat(hits.get(0).specId()).isEqualTo("23.501");
        // The TR was engineered to outrank the TS on raw ranks (inserted first,
        // wins every tie) — its demotion proves the 0.85 discount fired.
        SearchHit tr = hits.stream().filter(h -> h.specId().equals("23.799"))
                .findFirst().orElseThrow();
        assertThat(hits.get(0).score()).isGreaterThan(tr.score());
        assertThat(hits.get(0).score()).isBetween(0.97, 1.0);
        assertThat(tr.score()).isBetween(0.83, 0.86);
    }

    @Test
    @DisplayName("confidence: wide engineered margin with dual-retriever support is high")
    void confidenceHighFromRealSearch() throws Exception {
        List<SearchHit> hits = kb.hybridSearch("network slicing s-nssai slice",
                "network slicing s-nssai slice", axis(1), 5, SearchFilter.NONE);

        KbDataService.RetrievalConfidence conf = kb.confidenceOf(hits);
        assertThat(conf.level()).isEqualTo("high");
        assertThat(conf.support()).isEqualTo(2);
        assertThat(conf.margin()).isGreaterThanOrEqualTo(0.12);
        assertThat(conf.distinctSpecs()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("confidence: narrow margin but both retrievers agree is medium")
    void confidenceMediumWhenMarginNarrowButRetrieversAgree() throws Exception {
        // Same corpus, discount disabled: the TR/TS twins tie almost exactly, so
        // the margin collapses below 0.12 while support stays 2. Re-initialising
        // on the same file also exercises the "FTS5 already populated" branch.
        KbDataService kb2 = new KbDataService(KbSearchTestSupport.rerankOff(),
                KbSearchTestSupport.props(0.0, 1.0, 1.0),
                KbSearchTestSupport.lexicon(),
                KbSearchTestSupport.embedding());
        kb2.init(List.of(dbFile), new StartupState());

        List<SearchHit> hits = kb2.hybridSearch("network slicing s-nssai slice",
                "network slicing s-nssai slice", axis(1), 5, SearchFilter.NONE);

        KbDataService.RetrievalConfidence conf = kb2.confidenceOf(hits);
        assertThat(conf.margin()).isLessThan(0.12);
        assertThat(conf.level()).isEqualTo("medium");
        assertThat(conf.support()).isEqualTo(2);
    }

    @Test
    @DisplayName("stub spec is suppressed unless the query names it")
    void stubSpecSuppressedUnlessExplicitlyNamed() throws Exception {
        List<SearchHit> anonymous = kb.hybridSearch("stub spec", "stub spec",
                axis(3), 5, SearchFilter.NONE);
        assertThat(anonymous).noneMatch(h -> h.specId().equals("X.700"));

        List<SearchHit> named = kb.hybridSearch("X.700 stub spec", "X.700 stub spec",
                axis(3), 5, SearchFilter.NONE);
        assertThat(named.get(0).specId()).isEqualTo("X.700");
    }

    @Test
    @DisplayName("AND relaxation: full AND misses, AND minus the weakest term hits")
    void relaxedAndUsedWhenFullAndTooNarrow() throws Exception {
        // ims AND mmtel AND registration matches nothing (c1 lacks
        // "registration", b2 lacks ims/mmtel); relaxed ims AND mmtel finds c1.
        List<SearchHit> hits = kb.hybridSearch("mmtel ims registration",
                "mmtel ims registration", axis(2), 3, SearchFilter.NONE);

        assertThat(hits.get(0).specId()).isEqualTo("36.331");
        assertThat(hits.get(0).snippet()).contains("mmtel ims session");
    }

    @Test
    @DisplayName("vendor-alias pinned AND floor: canonical term alone reaches the chunk")
    void pinnedAliasFloorFiresWhenBothAndStepsMiss() throws Exception {
        // volte → mmtel substitution; "golden range allowed" never co-occurs
        // with mmtel, so AND and relaxed AND return nothing and the pinned
        // single-term AND("mmtel") must find 36.331.
        List<SearchHit> hits = kb.hybridSearch("volte golden range allowed",
                "volte golden range allowed", axis(2), 3, SearchFilter.NONE);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).specId()).isEqualTo("36.331");
    }

    @Test
    @DisplayName("OR fallback over the expanded query rescues an AND miss")
    void orFallbackUsesExpandedQueryTerms() throws Exception {
        // "voice" appears nowhere → every AND step fails; the expansion carries
        // "sip", which the OR leg matches in c1.
        List<SearchHit> hits = kb.hybridSearch("voice teardown",
                "voice teardown sip", axis(2), 3, SearchFilter.NONE);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).specId()).isEqualTo("36.331");
    }

    @Test
    @DisplayName("blank query: BM25 leg short-circuits, dense leg still answers")
    void blankQueryFallsBackToDenseOnly() throws Exception {
        List<SearchHit> hits = kb.hybridSearch("", "", axis(0), 3, SearchFilter.NONE);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).specId()).isEqualTo("38.331");
        // Only the dense retriever supported this spec.
        assertThat(hits.get(0).retrieverSupport()).isEqualTo(1);
        assertThat(hits.get(0).score()).isCloseTo(0.5, within(0.01));
    }

    @Test
    @DisplayName("hybridSearchDetailed without reranker reports no cap drops")
    void detailedResultWithoutRerankerHasEmptyCapDrops() throws Exception {
        KbDataService.HybridResult result = kb.hybridSearchDetailed("rach preamble",
                "rach preamble", axis(0), 3, SearchFilter.NONE, null);

        assertThat(result.hits()).isNotEmpty();
        assertThat(result.capDrops()).isEmpty();
    }
}

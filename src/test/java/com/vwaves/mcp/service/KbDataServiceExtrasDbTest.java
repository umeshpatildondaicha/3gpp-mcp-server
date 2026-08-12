package com.vwaves.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.vwaves.mcp.model.SearchFilter;
import com.vwaves.mcp.model.SearchHit;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two-database retrieval: a primary 3GPP DB plus an attached extras DB
 * (IETF-style content), covering the "1:"-prefixed chunk-id weighting,
 * the non-3GPP-intent gate on the extras OR fallback, cross-DB meta
 * reconciliation, and the dense-leg skip for a DB whose embedding blobs
 * have the wrong byte length.
 */
class KbDataServiceExtrasDbTest {

    @TempDir
    Path tmp;

    private Path mainDb;
    private Path extrasDb;
    private KbDataService kb;

    @BeforeEach
    void setUp() throws Exception {
        mainDb = tmp.resolve("main.db");
        extrasDb = tmp.resolve("extras.db");

        try (Connection conn = KbSearchTestSupport.open(mainDb)) {
            KbSearchTestSupport.createSchema(conn);
            KbSearchTestSupport.insertChunkWithVector(conn, "b1", "23.501", "Rel-17", "23",
                    "5G system architecture", "TS", "System architecture", 0, 2,
                    "Network slicing uses the s-nssai to select a network slice instance "
                            + "for each protocol data unit session.", 0f, 1f, 0f, 0f);
            KbSearchTestSupport.insertChunkWithVector(conn, "b2", "23.501", "Rel-17", "23",
                    "5G system architecture", "TS", "System architecture", 1, 2,
                    "The amf selects the smf for the requested dnn during "
                            + "registration procedures.", 0.2f, 1f, 0f, 0f);
            KbSearchTestSupport.insertChunkWithVector(conn, "a1", "38.331", "Rel-18", "38",
                    "NR; Radio Resource Control (RRC)", "TS", "NR RRC", 0, 2,
                    "The rach preamble transmission on msg1 uses the configured "
                            + "prach resources.", 1f, 0f, 0f, 0f);
            KbSearchTestSupport.insertChunkWithVector(conn, "a2", "38.331", "Rel-18", "38",
                    "NR; Radio Resource Control (RRC)", "TS", "NR RRC", 1, 2,
                    "Power ramping applies for each rach preamble retransmission "
                            + "attempt counter.", 1f, 0.2f, 0f, 0f);
            KbSearchTestSupport.insertMeta(conn, "embed_model", "test-model");
            KbSearchTestSupport.insertMeta(conn, "embed_dim", "4");
        }

        try (Connection conn = KbSearchTestSupport.open(extrasDb)) {
            KbSearchTestSupport.createSchema(conn);
            KbSearchTestSupport.insertChunkWithVector(conn, "r1", "RFC-5880", "IETF", "IETF",
                    "IETF RFCs", "RFC", "Bidirectional Forwarding Detection", 0, 2,
                    "The bfd protocol provides fast failure detection for forwarding "
                            + "paths between adjacent routers.", 0f, 0f, 0f, 1f);
            KbSearchTestSupport.insertChunkWithVector(conn, "r2", "RFC-5880", "IETF", "IETF",
                    "IETF RFCs", "RFC", "Bidirectional Forwarding Detection", 1, 2,
                    "An ospf neighbor relationship forms an adjacency across the "
                            + "shared network segment.", 0f, 0f, 1f, 0f);
            // Deliberately mismatching meta: model differs, dim differs.
            KbSearchTestSupport.insertMeta(conn, "embed_model", "other-model");
            KbSearchTestSupport.insertMeta(conn, "embed_dim", "8");
        }

        kb = new KbDataService(KbSearchTestSupport.rerankOff(),
                KbSearchTestSupport.defaultProps(),
                KbSearchTestSupport.lexicon(),
                KbSearchTestSupport.embedding());
        kb.init(List.of(mainDb, extrasDb), new StartupState());
    }

    @Test
    @DisplayName("both DBs load; extras chunk ids carry the 1: prefix")
    void bothDatabasesAreLoaded() throws Exception {
        assertThat(kb.vectorCount()).isEqualTo(6);
        assertThat(kb.totalChunks()).isEqualTo(6);
        assertThat(kb.totalSpecs()).isEqualTo(3);
        assertThat(kb.indexedSeries()).containsExactlyInAnyOrder("23", "38", "IETF");
    }

    @Test
    @DisplayName("non-3GPP intent term in the query neutralises the extras discount")
    void intentTermNeutralisesExtrasWeight() throws Exception {
        List<SearchHit> hits = kb.hybridSearch("bfd failure detection",
                "bfd failure detection", new float[]{0f, 0f, 0f, 1f}, 3, SearchFilter.NONE);

        SearchHit top = hits.get(0);
        assertThat(top.specId()).isEqualTo("RFC-5880");
        assertThat(top.chunkId()).startsWith("1:");
        // Rank 1 on both retrievers, weight 1.0 → full normalised score.
        assertThat(top.score()).isCloseTo(1.0, within(0.02));
    }

    @Test
    @DisplayName("without a non-3GPP marker the extras chunk keeps only half its score")
    void extrasDiscountHalvesScoreWithoutIntentTerm() throws Exception {
        // Same chunk, same ranks — but no bfd/ospf/rfc token in the query, so
        // the 0.5 extras discount applies to the "1:"-prefixed id.
        List<SearchHit> hits = kb.hybridSearch("failure detection forwarding",
                "failure detection forwarding", new float[]{0f, 0f, 0f, 1f}, 3,
                SearchFilter.NONE);

        SearchHit rfc = hits.stream().filter(h -> h.specId().equals("RFC-5880"))
                .findFirst().orElseThrow();
        assertThat(rfc.score()).isCloseTo(0.5, within(0.02));
    }

    @Test
    @DisplayName("extras DB gets the BM25 OR fallback only for non-3GPP-intent queries")
    void extrasOrFallbackGatedOnIntent() throws Exception {
        // AND(ospf, hello) matches nothing; "ospf" is a non-3GPP marker, so the
        // OR fallback runs against the extras DB and finds r2.
        List<SearchHit> withIntent = kb.hybridSearch("ospf hello", "ospf hello",
                new float[]{0f, 0f, 1f, 0f}, 3, SearchFilter.NONE);
        assertThat(withIntent.get(0).specId()).isEqualTo("RFC-5880");

        // Same BM25 miss without a marker: extras OR is skipped and the halved
        // dense-only score cannot beat the primary DB's chunks.
        List<SearchHit> withoutIntent = kb.hybridSearch("zebra hello", "zebra hello",
                new float[]{0f, 0f, 1f, 0f}, 3, SearchFilter.NONE);
        assertThat(withoutIntent).isNotEmpty();
        assertThat(withoutIntent.get(0).specId()).isNotEqualTo("RFC-5880");
    }

    @Test
    @DisplayName("meta mismatch across DBs resolves to the first DB's values")
    void metaMismatchKeepsFirstDbValues() throws Exception {
        assertThat(kb.embedModelName()).isEqualTo("test-model");
        assertThat(kb.embedDimFromMeta()).isEqualTo(4);
    }

    @Test
    @DisplayName("a DB with wrong-size vectors keeps BM25 but loses its dense leg")
    void wrongDimensionDbFallsBackToBm25Only() throws Exception {
        Path goodDb = tmp.resolve("good.db");
        Path badDb = tmp.resolve("bad-dim.db");
        try (Connection conn = KbSearchTestSupport.open(goodDb)) {
            KbSearchTestSupport.createSchema(conn);
            KbSearchTestSupport.insertChunkWithVector(conn, "g1", "38.331", "Rel-18", "38",
                    "NR; RRC", "TS", "NR RRC", 0, 2,
                    "The rach preamble transmission on msg1 uses the configured "
                            + "prach resources.", 1f, 0f, 0f, 0f);
            KbSearchTestSupport.insertChunkWithVector(conn, "g2", "38.331", "Rel-18", "38",
                    "NR; RRC", "TS", "NR RRC", 1, 2,
                    "Power ramping applies for each rach preamble retransmission "
                            + "attempt counter.", 1f, 0.2f, 0f, 0f);
            KbSearchTestSupport.insertMeta(conn, "embed_model", "test-model");
        }
        try (Connection conn = KbSearchTestSupport.open(badDb)) {
            KbSearchTestSupport.createSchema(conn);
            KbSearchTestSupport.insertChunk(conn, "x1", "RFC-5880", "IETF", "IETF",
                    "IETF RFCs", "RFC", "BFD", 0, 2,
                    "The bfd protocol provides fast failure detection for forwarding "
                            + "paths between adjacent routers.");
            KbSearchTestSupport.insertChunk(conn, "x2", "RFC-5880", "IETF", "IETF",
                    "IETF RFCs", "RFC", "BFD", 1, 2,
                    "Session state changes are signalled through the bfd control "
                            + "packet exchange mechanism.");
            // 2 floats = 8 bytes, but dim=4 expects 16: dense loading must skip
            // this DB entirely while FTS/BM25 stays available.
            KbSearchTestSupport.insertEmbeddingBlob(conn, "x1", KbSearchTestSupport.blob(1f, 0f));
            KbSearchTestSupport.insertEmbeddingBlob(conn, "x2", KbSearchTestSupport.blob(0f, 1f));
            KbSearchTestSupport.insertMeta(conn, "embed_model", "test-model");
        }

        KbDataService kb2 = new KbDataService(KbSearchTestSupport.rerankOff(),
                KbSearchTestSupport.defaultProps(),
                KbSearchTestSupport.lexicon(),
                KbSearchTestSupport.embedding());
        kb2.init(List.of(goodDb, badDb), new StartupState());

        // Only the good DB's two vectors made it into the dense index.
        assertThat(kb2.vectorCount()).isEqualTo(2);
        // The bad-dim DB is still reachable through BM25 ("bfd" neutralises the
        // extras discount so the AND match ranks it).
        List<SearchHit> hits = kb2.hybridSearch("bfd failure detection",
                "bfd failure detection", new float[]{1f, 0f, 0f, 0f}, 3, SearchFilter.NONE);
        assertThat(hits).anyMatch(h -> h.specId().equals("RFC-5880"));
    }
}

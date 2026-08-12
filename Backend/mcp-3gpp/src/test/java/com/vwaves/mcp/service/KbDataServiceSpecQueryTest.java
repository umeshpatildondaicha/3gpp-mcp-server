package com.vwaves.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.vwaves.mcp.model.SearchHit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DB-backed coverage for the non-hybrid retrieval surface of
 * {@link KbDataService}: the dense-only {@code search()} API, spec-chunk
 * queries (chunk_index order and FTS-ranked), {@code listSpecs}, the stats
 * accessors served from init-time state, and the FTS5 bootstrap branch where
 * the schema pre-exists but its inverted index was never populated.
 */
class KbDataServiceSpecQueryTest {

    @TempDir
    Path tmp;

    private Path dbFile;
    private KbDataService kb;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = tmp.resolve("specquery-corpus.db");
        Files.deleteIfExists(dbFile);
        try (Connection conn = KbSearchTestSupport.open(dbFile)) {
            KbDataServiceHybridSearchTest.seedCorpus(conn);
        }
        kb = new KbDataService(KbSearchTestSupport.rerankOff(),
                KbSearchTestSupport.defaultProps(),
                KbSearchTestSupport.lexicon(),
                KbSearchTestSupport.embedding());
        kb.init(List.of(dbFile), new StartupState());
    }

    // ── Dense-only search() ───────────────────────────────────────────────────

    @Test
    @DisplayName("dense search ranks by cosine, drops binary text, caps per spec")
    void denseSearchRanksCapsAndDropsBinary() throws Exception {
        List<SearchHit> hits = kb.search(new float[]{1f, 0f, 0f, 0f}, 2, null, null, null);

        assertThat(hits).hasSize(2);
        // a1 is an exact axis match; the equally-scored 38.901 chunk is binary
        // and the other 38.331 chunks lose to the per-spec cap.
        assertThat(hits.get(0).specId()).isEqualTo("38.331");
        assertThat(hits.get(0).score()).isCloseTo(1.0, within(0.001));
        assertThat(hits.get(1).specId()).isEqualTo("23.501");
        assertThat(hits.get(1).score()).isCloseTo(0.1961, within(0.001));
    }

    @Test
    @DisplayName("dense search honours the series filter")
    void denseSearchHonoursSeriesFilter() throws Exception {
        List<SearchHit> hits = kb.search(new float[]{0f, 0f, 1f, 0f}, 2, "36", null, null);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).specId()).isEqualTo("36.331");
        assertThat(hits.get(0).snippet()).contains("mmtel ims session");
    }

    // ── getSpecChunks ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getSpecChunks without query returns chunk_index order")
    void specChunksOrderedByChunkIndex() throws Exception {
        List<Map<String, Object>> rows = kb.getSpecChunks("38.331", 2);

        assertThat(rows).hasSize(2);
        assertThat((String) rows.get(0).get("text")).contains("rach preamble transmission");
        assertThat((String) rows.get(1).get("text")).contains("Power ramping");
    }

    @Test
    @DisplayName("getSpecChunks with query returns FTS-ranked chunks, not the cover page")
    void specChunksRankedByQueryRelevance() throws Exception {
        List<Map<String, Object>> rows =
                kb.getSpecChunks("38.331", 1, "rrc reconfiguration measurement");

        assertThat(rows).hasSize(1);
        assertThat((String) rows.get(0).get("text")).contains("rrc reconfiguration message");
    }

    @Test
    @DisplayName("getSpecChunks falls back to document order when the query matches nothing")
    void specChunksQueryFallsBackWhenNoMatch() throws Exception {
        List<Map<String, Object>> noMatch = kb.getSpecChunks("38.331", 1, "zzzz qqqq");
        assertThat((String) noMatch.get(0).get("text")).contains("rach preamble transmission");

        // Stop-word-only query yields no FTS terms at all → same fallback.
        List<Map<String, Object>> noTerms = kb.getSpecChunks("38.331", 1, "the of and");
        assertThat((String) noTerms.get(0).get("text")).contains("rach preamble transmission");
    }

    @Test
    @DisplayName("getSpecChunks for an unknown spec is empty")
    void specChunksUnknownSpecEmpty() throws Exception {
        assertThat(kb.getSpecChunks("99.999", 5)).isEmpty();
    }

    // ── listSpecs ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("listSpecs enumerates every spec sorted by id with total_chunks")
    void listSpecsAll() throws Exception {
        List<Map<String, Object>> specs = kb.listSpecs(null, null);

        assertThat(specs).extracting(m -> m.get("spec_id"))
                .containsExactly("23.501", "23.799", "36.331", "38.331", "38.901", "X.700");
        Map<String, Object> first = specs.get(0);
        assertThat(first).containsEntry("doc_type", "TS");
        assertThat(((Number) first.get("total_chunks")).intValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("listSpecs filters by series and by release")
    void listSpecsFiltered() throws Exception {
        assertThat(kb.listSpecs("23", null)).extracting(m -> m.get("spec_id"))
                .containsExactly("23.501", "23.799");
        assertThat(kb.listSpecs(null, "Rel-15")).extracting(m -> m.get("spec_id"))
                .containsExactly("36.331");
        assertThat(kb.listSpecs("23", "Rel-17")).extracting(m -> m.get("spec_id"))
                .containsExactly("23.501");
    }

    // ── Stats / accessors ─────────────────────────────────────────────────────

    @Test
    @DisplayName("init-time stats: vectors, chunks, specs, series, stubs, embed meta")
    void statsReflectTheLoadedCorpus() throws Exception {
        assertThat(kb.vectorCount()).isEqualTo(10);
        assertThat(kb.totalChunks()).isEqualTo(10);
        assertThat(kb.totalSpecs()).isEqualTo(6);
        assertThat(kb.indexedSeries()).containsExactlyInAnyOrder("23", "36", "38", "X");
        assertThat(kb.allSpecIds()).containsExactlyInAnyOrder(
                "23.501", "23.799", "36.331", "38.331", "38.901", "X.700");
        // One-chunk specs: 23.799, 38.901 and X.700.
        assertThat(kb.stubSpecCount()).isEqualTo(3);
        assertThat(kb.embedModelName()).isEqualTo("test-model");
        assertThat(kb.embedDimFromMeta()).isEqualTo(4);
    }

    @Test
    @DisplayName("adjacentContext is empty for a spec the index does not know")
    void adjacentContextUnknownSpecIsEmpty() throws Exception {
        SearchHit hit = new SearchHit(0.9, "99.999", "Rel-18", "title", "desc",
                "snippet", "0:nope", "TS", 2, 0);
        assertThat(kb.adjacentContext(hit, 100).isEmpty()).isTrue();
    }

    // ── FTS5 bootstrap variants ───────────────────────────────────────────────

    @Test
    @DisplayName("pre-existing but empty chunks_fts schema is rebuilt at init")
    void emptyFtsSchemaIsRebuiltOnInit() throws Exception {
        Path db = tmp.resolve("prebuilt-fts.db");
        try (Connection conn = KbSearchTestSupport.open(db)) {
            KbSearchTestSupport.createSchema(conn);
            KbSearchTestSupport.insertChunk(conn, "p1", "24.301", "Rel-17", "24",
                    "NAS", "TS", "NAS protocol", 0, 1,
                    "The attach procedure establishes nas signalling between the ue and the mme "
                            + "using the initial attach request message flow.");
            // FTS5 schema created ahead of time, but 'rebuild' never ran: the
            // internal chunks_fts_idx has zero rows and init() must populate it.
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE VIRTUAL TABLE chunks_fts USING fts5(" +
                        "id UNINDEXED, text, title, spec_id, series_desc, " +
                        "content='chunks', content_rowid='rowid')");
            }
            KbSearchTestSupport.insertMeta(conn, "embed_model", "test-model");
        }

        KbDataService kb2 = new KbDataService(KbSearchTestSupport.rerankOff(),
                KbSearchTestSupport.defaultProps(),
                KbSearchTestSupport.lexicon(),
                KbSearchTestSupport.embedding());
        kb2.init(List.of(db), new StartupState());

        // No embeddings at all: the dense leg is empty and BM25 alone must find
        // the chunk through the index that init() just rebuilt.
        assertThat(kb2.vectorCount()).isZero();
        List<SearchHit> hits = kb2.hybridSearch("attach procedure", "attach procedure",
                new float[]{0f, 0f, 0f, 0f}, 3, com.vwaves.mcp.model.SearchFilter.NONE);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).specId()).isEqualTo("24.301");
        assertThat(hits.get(0).retrieverSupport()).isEqualTo(1);
    }
}

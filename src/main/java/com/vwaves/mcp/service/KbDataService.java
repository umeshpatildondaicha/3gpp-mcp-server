package com.vwaves.mcp.service;

import com.vwaves.mcp.config.RetrievalProperties;
import com.vwaves.mcp.model.ChunkMeta;
import com.vwaves.mcp.model.SearchFilter;
import com.vwaves.mcp.model.SearchHit;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class KbDataService {
    private static final Logger log = LoggerFactory.getLogger(KbDataService.class);

    // ── Named constants (values unchanged from the original inline literals) ──

    /** Bytes per float32 component in an embedding vector blob. */
    private static final int FLOAT_BYTES = 4;
    /** Chunk-count ceiling used in the load-time stub statistic log line. */
    private static final int STUB_LOG_MAX_CHUNKS = 2;
    /** Round search scores to 4 decimals (multiply, round, divide). */
    private static final double SCORE_ROUND_FACTOR = 10000.0d;
    /** Round confidence margin/top-score to 3 decimals. */
    private static final double CONFIDENCE_ROUND_FACTOR = 1000.0;
    /** Chunks in each retriever's top tier considered for the co-occurrence boost. */
    private static final int CO_OCCURRENCE_TOP_N = 10;
    /** Number of fused rank lists (dense + BM25); numerator of the max RRF score. */
    private static final double RRF_LIST_COUNT = 2.0;
    /** Retriever support value meaning "both retrievers agree". */
    private static final int FULL_RETRIEVER_SUPPORT = 2;
    /** Queries with at least this many short original terms skip digit expansions. */
    private static final int SPECIFIC_QUERY_SHORT_TERM_COUNT = 4;
    /** Maximum length for a term to count as "short" (nssai, rach, pdcp, qos). */
    private static final int SHORT_TERM_MAX_LENGTH = 6;
    /** Maximum length for a term to count as "medium" (slicing, bearer, handover). */
    private static final int MEDIUM_TERM_MAX_LENGTH = 9;
    /** Cap on digit-containing terms admitted into the AND predicate. */
    private static final int MAX_DIGIT_AND_TERMS = 6;
    /** BM25 fetch oversampling factor when in-memory filters are active. */
    private static final int FILTER_OVERSAMPLE_FACTOR = 10;
    /** termSpecificity score for short terms. */
    private static final int SPECIFICITY_SHORT = 2;
    /** termSpecificity score for medium terms. */
    private static final int SPECIFICITY_MEDIUM = 3;
    /** termSpecificity score for long common English words (expansion noise). */
    private static final int SPECIFICITY_LONG = 4;
    /** Minimum token length kept by extractFtsTerms. */
    private static final int MIN_FTS_TERM_LENGTH = 2;
    /** Intent terms at or below this length require a word-boundary match. */
    private static final int SHORT_INTENT_TERM_MAX_LENGTH = 3;
    /** JDBC index of the second SQL placeholder. */
    private static final int SECOND_SQL_PARAM = 2;
    /** JDBC index of the third SQL placeholder. */
    private static final int THIRD_SQL_PARAM = 3;
    /** Regex group holding the IE definition body in ieDefinitionPattern. */
    private static final int IE_DEF_GROUP = 2;
    /** Characters of context captured before an IE definition match. */
    private static final int IE_CONTEXT_BEFORE_CHARS = 160;
    /** Characters of context captured after an IE definition match. */
    private static final int IE_CONTEXT_AFTER_CHARS = 60;
    /** Leading characters sampled when sniffing for binary chunk text. */
    private static final int BINARY_SNIFF_CHARS = 600;
    /** Lowest printable ASCII code point (exclusive lower bound for "bad" chars). */
    private static final int MIN_PRINTABLE_CHAR = 32;
    /** Highest printable ASCII code point (exclusive upper bound for "bad" chars). */
    private static final int MAX_PRINTABLE_CHAR = 126;
    /** bad * ratio > length  ⇔  more than 1/ratio of sampled chars are non-printable. */
    private static final int BINARY_BAD_CHAR_RATIO = 5;

    private static final String COL_SPEC_ID = "spec_id";
    private static final String COL_RELEASE = "release";
    private static final String SQL_SELECT_CHUNK_TEXT = "SELECT text FROM chunks WHERE id=?";
    /** Characters stripped from query tokens before FTS matching. */
    private static final String NON_TERM_CHARS_RE = "[^A-Za-z0-9-]";

    private final RerankService rerankService;
    private final RetrievalProperties props;
    private final LexiconService lexicon;
    private final EmbeddingService embeddingService;

    public KbDataService(RerankService rerankService,
                         RetrievalProperties props,
                         LexiconService lexicon,
                         EmbeddingService embeddingService) {
        this.rerankService = rerankService;
        this.props = props;
        this.lexicon = lexicon;
        this.embeddingService = embeddingService;
    }

    /** Vector dimension; sourced from EmbeddingService at startup, validated against ingestion meta. */
    private int dim() { return embeddingService.dim(); }

    // Chunk IDs are prefixed "dbIdx:realId" to be unique across two databases.
    private final AtomicReference<float[]>                atomicEmbeddings = new AtomicReference<>();
    private final AtomicReference<String[]>               atomicChunkIds   = new AtomicReference<>();
    private final AtomicReference<Map<String, ChunkMeta>> atomicChunkMeta  = new AtomicReference<>();
    // Per-spec chunk count, used by isStubSpec() to suppress 1-2 chunk
    // "registered but not really ingested" specs from search results.
    // Built once during loadChunkMeta(); never mutated after init.
    private final AtomicReference<Map<String, Integer>>   atomicSpecChunkCounts = new AtomicReference<>();
    // spec_id → doc_type ('TS' / 'TR' / etc.); built once at init for O(1) post-rerank lookup
    // when only the SearchHit (no ChunkMeta) is available.
    private final AtomicReference<Map<String, String>>    atomicDocTypeBySpecId = new AtomicReference<>();
    // spec_id → (chunk_index → prefixed chunk id), for adjacentContext(). Built once
    // during loadChunkMeta() from the same rows as chunkMeta; only chunks with a
    // known (non -1) chunk_index are entered.
    private final AtomicReference<Map<String, NavigableMap<Integer, String>>> atomicSpecChunkIndex = new AtomicReference<>();
    private final AtomicReference<List<Connection>>       atomicConnections = new AtomicReference<>();

    private List<Connection> connections()                { return atomicConnections.get(); }
    private Map<String, ChunkMeta> chunkMeta()            { return atomicChunkMeta.get(); }
    private Map<String, Integer> specChunkCounts()        { return atomicSpecChunkCounts.get(); }

    // ── Initialization ────────────────────────────────────────────────────────

    public void init(List<Path> dbPaths, StartupState startupState) throws SQLException, IOException {
        startupState.phase("loading-db");
        List<Connection> conns = new ArrayList<>();
        for (Path p : dbPaths) {
            log.info("opening DB: {}", p.toAbsolutePath());
            conns.add(DriverManager.getConnection("jdbc:sqlite:" + p.toAbsolutePath()));
        }
        this.atomicConnections.set(conns);
        loadEmbeddings();
        loadChunkMeta();
        startupState.phase("building-fts");
        buildFts5IfMissing();
    }

    // ── FTS5 index ────────────────────────────────────────────────────────────

    private void buildFts5IfMissing() throws SQLException {
        for (Connection conn : connections()) {
            buildFts5ForConn(conn);
        }
    }

    private void buildFts5ForConn(Connection conn) throws SQLException {
        boolean tableExists;
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='chunks_fts'")) {
            tableExists = rs.next();
        }

        if (tableExists) {
            // Check whether the FTS5 inverted index actually has data.
            // COUNT(*) on a content-FTS5 table counts the content table rows, NOT the index entries.
            // chunks_fts_idx is SQLite-internal: 0 rows means the index was never built.
            long idxRows = 0;
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) AS n FROM chunks_fts_idx")) {
                idxRows = rs.next() ? rs.getLong("n") : 0;
            }
            if (idxRows > 0) {
                log.info("FTS5 index already present and populated ({} idx rows)", idxRows);
                return;
            }
            log.info("FTS5 schema exists but index is empty — running rebuild (one-time)...");
        } else {
            log.info("Creating FTS5 content table and rebuilding index (one-time)...");
            try (Statement s = conn.createStatement()) {
                // content FTS5 table — text stays in chunks; FTS5 stores only the inverted index.
                s.executeUpdate("CREATE VIRTUAL TABLE chunks_fts USING fts5(" +
                        "id UNINDEXED, text, title, spec_id, series_desc, " +
                        "content='chunks', content_rowid='rowid')");
            }
        }

        long t0 = System.currentTimeMillis();
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("INSERT INTO chunks_fts(chunks_fts) VALUES('rebuild')");
        }
        long idxRows = 0;
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) AS n FROM chunks_fts_idx")) {
            idxRows = rs.next() ? rs.getLong("n") : 0;
        }
        log.info("FTS5 rebuild complete: {} idx rows, {} ms", idxRows, System.currentTimeMillis() - t0);
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadEmbeddings() throws SQLException, IOException {
        List<Connection> conns = connections();
        log.info("loading embeddings from {} DB(s)...", conns.size());
        List<String> ids    = new ArrayList<>();
        List<float[]> vecs  = new ArrayList<>();

        for (int dbIdx = 0; dbIdx < conns.size(); dbIdx++) {
            Connection conn = conns.get(dbIdx);

            // Verify vector dimension before bulk loading: skip DBs whose embed model
            // produced a different vector size (e.g. bge-m3 → 1024-dim vs 384-dim here).
            // Those DBs still participate in BM25/FTS5 search; only dense is skipped.
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT vector FROM embeddings LIMIT 1")) {
                if (rs.next()) {
                    byte[] probe = rs.getBytes("vector");
                    int probeLen = probe == null ? 0 : probe.length;
                    if (probeLen != dim() * FLOAT_BYTES) {
                        log.warn("DB[{}] vector byte-length {} ≠ expected {} (dim={}). " +
                                "Dense search disabled for this DB; BM25 still active.",
                                dbIdx, probeLen, dim() * FLOAT_BYTES, dim());
                        continue;
                    }
                }
            }

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT chunk_id, vector FROM embeddings ORDER BY rowid")) {
                while (rs.next()) {
                    ids.add(dbIdx + ":" + rs.getString("chunk_id"));
                    vecs.add(vectorFromBlob(rs.getBytes("vector")));
                }
            }
        }

        int n = vecs.size();
        int d = dim();
        float[] flat   = new float[n * d];
        String[] idArr = new String[n];
        for (int i = 0; i < n; i++) {
            idArr[i] = ids.get(i);
            float[] v = vecs.get(i);
            l2Normalize(v);
            System.arraycopy(v, 0, flat, i * d, d);
        }
        this.atomicChunkIds.set(idArr);
        this.atomicEmbeddings.set(flat);
        if (log.isInfoEnabled()) {
            log.info("{} vectors loaded from {} DB(s), L2-normalised ({} MB RAM)",
                    String.format("%,d", n), conns.size(), (n * d * FLOAT_BYTES) / 1_000_000);
        }
    }

    private static void l2Normalize(float[] v) {
        double sum = 0.0;
        for (float x : v) sum += (double) x * x;
        double mag = Math.sqrt(sum);
        if (mag <= 0) return;
        float inv = (float) (1.0 / mag);
        for (int i = 0; i < v.length; i++) v[i] *= inv;
    }

    private void loadChunkMeta() throws SQLException {
        Map<String, ChunkMeta> meta = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> docTypes = new HashMap<>();
        Map<String, NavigableMap<Integer, String>> chunkIndex = new HashMap<>();
        List<Connection> conns = connections();
        for (int dbIdx = 0; dbIdx < conns.size(); dbIdx++) {
            Connection conn = conns.get(dbIdx);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT id, spec_id, release, series, series_desc, doc_type, title, chunk_index FROM chunks")) {
                while (rs.next()) {
                    String prefixed = dbIdx + ":" + rs.getString("id");
                    String specId   = rs.getString(COL_SPEC_ID);
                    String docType  = rs.getString("doc_type");
                    int idx = parseChunkIndex(rs.getString("chunk_index"));
                    meta.put(prefixed, new ChunkMeta(
                            prefixed,
                            specId,
                            rs.getString(COL_RELEASE),
                            rs.getString("series"),
                            rs.getString("series_desc"),
                            docType,
                            rs.getString("title"),
                            idx
                    ));
                    counts.merge(specId, 1, Integer::sum);
                    if (docType != null) docTypes.putIfAbsent(specId, docType);
                    if (idx >= 0) {
                        chunkIndex.computeIfAbsent(specId, k -> new TreeMap<>()).put(idx, prefixed);
                    }
                }
            }
        }
        this.atomicChunkMeta.set(meta);
        this.atomicSpecChunkCounts.set(counts);
        this.atomicDocTypeBySpecId.set(docTypes);
        this.atomicSpecChunkIndex.set(chunkIndex);
        long stubCount = counts.values().stream().filter(n -> n <= STUB_LOG_MAX_CHUNKS).count();
        log.info("chunk metadata loaded: {} chunks across {} specs ({} are stubs ≤2 chunks)",
                meta.size(), counts.size(), stubCount);
    }

    /** chunk_index is TEXT in this schema; -1 when absent or non-numeric. */
    private static int parseChunkIndex(String raw) {
        if (raw == null || raw.isBlank()) return -1;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ── Prefixed-ID helpers ───────────────────────────────────────────────────

    private int dbOf(String prefixedId) {
        return Integer.parseInt(prefixedId.substring(0, prefixedId.indexOf(':')));
    }

    private String realId(String prefixedId) {
        return prefixedId.substring(prefixedId.indexOf(':') + 1);
    }

    // ── Public search API ─────────────────────────────────────────────────────

    public List<SearchHit> search(float[] queryVector, int topK,
                                   String series, String release, String docType) throws SQLException {
        int oversample = Math.min(Math.max(topK * props.binaryFilterOversample(), topK), props.maxOversample());
        List<ScoredId> scored = cosineTopK(queryVector, oversample, series, release, docType);

        List<SearchHit> out = new ArrayList<>(topK);
        Map<String, Integer> specCount = new HashMap<>();
        for (ScoredId id : scored) {
            if (out.size() >= topK) break;
            SearchHit hit = toDenseHit(id, specCount);
            if (hit != null) out.add(hit);
        }
        return out;
    }

    /** One dense hit for {@link #search}; null when the chunk is filtered out. */
    private SearchHit toDenseHit(ScoredId id, Map<String, Integer> specCount) throws SQLException {
        ChunkMeta meta = chunkMeta().get(id.chunkId());
        if (meta == null) return null;
        if (specCount.merge(meta.specId(), 1, Integer::sum) > props.maxHybridPerSpec()) return null;
        Connection conn = connections().get(dbOf(id.chunkId()));
        try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_CHUNK_TEXT)) {
            ps.setString(1, realId(id.chunkId()));
            try (ResultSet rs = ps.executeQuery()) {
                String text = rs.next() ? rs.getString("text") : "";
                if (looksBinary(text)) return null;
                double rounded = Math.round(id.score() * SCORE_ROUND_FACTOR) / SCORE_ROUND_FACTOR;
                return new SearchHit(rounded, meta.specId(), meta.release(),
                        meta.title(), meta.seriesDesc(), text == null ? "" : text,
                        id.chunkId(), meta.docType(), 0, meta.chunkIndex());
            }
        }
    }

    /**
     * A spec whose chunks were cut purely by the per-spec diversity cap after
     * already clearing the score floor and the reranker — i.e. chunks judged
     * relevant, that lost their seat only because the spec had already filled
     * its quota (default 1). Distinct from a low-score drop: this spec had
     * MORE to say and the cap is the only reason the caller doesn't see it.
     *
     * @param droppedCount how many chunks were cut this way for this spec
     * @param droppedTopScore the highest score among the chunks that were cut
     */
    public record CapDrop(String specId, int droppedCount, double droppedTopScore) {}

    /** hybridSearch's usual hits, plus which specs lost same-spec chunks to the
     *  per-spec cap. Most callers should keep using the plain-hits overloads
     *  below; only search3gpp surfaces capDrops to the caller as a note. */
    public record HybridResult(List<SearchHit> hits, Map<String, CapDrop> capDrops) {}

    /**
     * Hybrid dense + BM25 retrieval with Reciprocal Rank Fusion.
     *
     * Improvements over naive RRF:
     *   1. Co-occurrence boost: chunks ranked top-10 by BOTH retrievers get 1.5× score.
     *   2. Per-spec diversity cap: at most MAX_HYBRID_PER_SPEC chunks per spec in output.
     *   3. Score threshold: results below MIN_RESULT_SCORE are silently dropped.
     *   4. BM25 AND query uses originalQuery terms only (no glossary-expansion noise).
     *      BM25 OR fallback uses expandedQuery for recall.
     *   5. Study reports (23.700-xx, 36.750…) are discounted in RRF so canonical TSes win.
     *   6. Supplementary DB results are discounted to prevent NFV/ITU/ORAN pollution.
     *   7. Dense path has its own per-spec cap to prevent embedding-space clustering.
     *
     * @param originalQuery raw user query (used for AND precision path)
     * @param expandedQuery glossary-expanded query (used for OR recall path)
     */
    public List<SearchHit> hybridSearch(
            String originalQuery, String expandedQuery, float[] queryVector, int topK,
            SearchFilter filter
    ) throws SQLException {
        return hybridSearch(originalQuery, expandedQuery, queryVector, topK, filter, null);
    }

    /**
     * @param maxPerSpecOverride how many chunks a single spec may contribute to the
     *        final result, overriding {@code maxHybridPerSpec}. The default of 1
     *        maximises spec diversity, which is right for "which spec answers
     *        this" — but wrong for "what does THIS spec say about this IE". A
     *        parameter definition often sits in an ASN.1 block that the
     *        cross-encoder scores below the prose chunk that merely mentions the
     *        IE, so with a cap of 1 the definition can never be reached.
     */
    public List<SearchHit> hybridSearch(
            String originalQuery, String expandedQuery, float[] queryVector, int topK,
            SearchFilter filter, Integer maxPerSpecOverride
    ) throws SQLException {
        return hybridSearchDetailed(originalQuery, expandedQuery, queryVector, topK,
                filter, maxPerSpecOverride).hits();
    }

    /**
     * Same retrieval as {@link #hybridSearch}, plus a same-spec cap-drop report
     * so a caller can tell "nothing else relevant" from "the per-spec cap hid
     * something" — see {@link CapDrop}. Only search3gpp needs this distinction
     * today; every other caller should keep using the plain-hits overload.
     */
    public HybridResult hybridSearchDetailed(
            String originalQuery, String expandedQuery, float[] queryVector, int topK,
            SearchFilter filter, Integer maxPerSpecOverride
    ) throws SQLException {
        String series  = filter.series();
        String release = filter.release();
        String docType = filter.docType();
        // The candidate pool is deliberately INDEPENDENT of topK. Sizing it as
        // topK * mult meant topK changed WHICH documents were considered, not just
        // how many were returned: measured 2026-07-28 on "pseudowire down link
        // down", topK=5 (pool 50) returned 23.802/36.803/36.300/36.143/36.465
        // while topK=10 (pool 100) returned RFC-2328 at rank 1 and RFC-2863 at
        // rank 4 — neither RFC appearing at all in the smaller pool. A top-5 that
        // is not a prefix of the top-10 is indefensible, and it bites hardest in
        // agent mode where the model picks topK freely.
        //
        // Using the configured maximum for every query makes the ranking a single
        // stable ordering that topK only truncates. Cost is bounded: the dense leg
        // already scans the whole in-RAM index, and the cross-encoder still sees
        // only rerank-candidates (40), so the extra work is BM25 depth and fusion.
        int candidatePool = props.maxHybridCandidates();
        List<ScoredId> dense = cosineTopK(queryVector, candidatePool, series, release, docType);
        List<ScoredId> bm25  = bm25TopK(originalQuery, expandedQuery, candidatePool, series, release, docType);

        // Retriever agreement, at SPEC level: which specs each retriever put in its
        // own top tier, independently of the other. Two retrievers agreeing is a
        // different kind of evidence from a wide score margin, which only compares
        // a hit to its neighbours in one already-fused ranking.
        Set<String> denseTopSpecs = topSpecs(dense, props.agreementTopN());
        Set<String> bm25TopSpecs  = topSpecs(bm25,  props.agreementTopN());

        final int rrfK = props.rrfK();
        Map<String, Double> rrf = fuseWithRrf(dense, bm25, rrfK);
        applyCoOccurrenceBoost(rrf, dense, bm25);
        final double extrasWeight = extrasDbWeightFor(originalQuery, series);
        final double studyReportDiscount = props.studyReportDiscount();
        applyRrfDiscounts(rrf, extrasWeight, studyReportDiscount);

        // maxRrf = theoretical max score for item ranked #1 in both lists (no boost)
        double maxRrf = RRF_LIST_COUNT / (rrfK + 1);

        List<Map.Entry<String, Double>> sorted = new ArrayList<>(rrf.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue().reversed());

        // When the cross-encoder reranker is active, collect a larger candidate pool
        // and let the reranker pick the best topK. Without the reranker, fall back to
        // the conservative diversity cap.
        boolean useReranker  = rerankService.isReady();
        int targetPool       = useReranker ? Math.max(props.rerankCandidates(), topK) : topK;
        int perSpecCap       = useReranker ? props.maxRerankPerSpec() : props.maxHybridPerSpec();
        if (maxPerSpecOverride != null) perSpecCap = Math.max(perSpecCap, maxPerSpecOverride);

        FusedHitContext ctx = new FusedHitContext(perSpecCap, maxRrf, originalQuery,
                denseTopSpecs, bm25TopSpecs);
        List<SearchHit> out = collectFusedHits(sorted, targetPool, ctx);

        // Stage 2: cross-encoder reranking over the candidate pool.
        //
        // The reranker replaces RRF scores entirely, so the RRF-stage discounts are
        // lost. We re-apply them to reranker output AND enforce the final per-spec
        // cap (props.maxHybridPerSpec) so a single TR cannot occupy multiple slots
        // even after reranking — the pre-rerank pool used the looser
        // props.maxRerankPerSpec cap to give the reranker more candidates to choose
        // from.
        if (useReranker) {
            return rerankAndCap(originalQuery, out, topK, maxPerSpecOverride, release,
                    extrasWeight, studyReportDiscount);
        }
        return new HybridResult(out, Map.of());
    }

    /** RRF fusion (Cormack et al.) of the dense and BM25 rank lists. */
    private static Map<String, Double> fuseWithRrf(List<ScoredId> dense, List<ScoredId> bm25, int rrfK) {
        Map<String, Double> rrf = new LinkedHashMap<>();
        for (int r = 0; r < dense.size(); r++)
            rrf.merge(dense.get(r).chunkId(), 1.0 / (rrfK + r + 1), Double::sum);
        for (int r = 0; r < bm25.size(); r++)
            rrf.merge(bm25.get(r).chunkId(), 1.0 / (rrfK + r + 1), Double::sum);
        return rrf;
    }

    /** Co-occurrence bonus: boost chunks both retrievers agree on. */
    private void applyCoOccurrenceBoost(Map<String, Double> rrf, List<ScoredId> dense, List<ScoredId> bm25) {
        Set<String> denseTop = dense.stream().limit(CO_OCCURRENCE_TOP_N)
                .map(ScoredId::chunkId).collect(Collectors.toSet());
        Set<String> bm25Top  = bm25.stream().limit(CO_OCCURRENCE_TOP_N)
                .map(ScoredId::chunkId).collect(Collectors.toSet());
        final double coOccurrenceBoost = props.coOccurrenceBoost();
        rrf.replaceAll((id, score) ->
                denseTop.contains(id) && bm25Top.contains(id) ? score * coOccurrenceBoost : score);
    }

    /**
     * Study-report and extras-DB discounts applied after co-occurrence boost.
     * Study reports discuss topics more densely than the normative TS, so they
     * outrank the authoritative spec in BM25 unless penalised. Source of truth
     * for TR detection is the DB doc_type column; spec_id range is fallback.
     */
    private void applyRrfDiscounts(Map<String, Double> rrf, double extrasWeight, double studyReportDiscount) {
        Map<String, ChunkMeta> metaForDiscount = chunkMeta();
        rrf.replaceAll((id, score) -> {
            double s = score;
            ChunkMeta m = metaForDiscount == null ? null : metaForDiscount.get(id);
            if (m != null && isStudyReport(m)) s *= studyReportDiscount;
            if (id.startsWith("1:"))           s *= extrasWeight;
            return s;
        });
    }

    /** Read-only context shared by every fused-hit materialisation in one query. */
    private record FusedHitContext(int perSpecCap, double maxRrf, String originalQuery,
                                   Set<String> denseTopSpecs, Set<String> bm25TopSpecs) {}

    private List<SearchHit> collectFusedHits(List<Map.Entry<String, Double>> sorted,
                                             int targetPool, FusedHitContext ctx) throws SQLException {
        List<SearchHit> out = new ArrayList<>(targetPool);
        Map<String, Integer> specCount = new HashMap<>();
        for (Map.Entry<String, Double> e : sorted) {
            if (out.size() >= targetPool) break;
            SearchHit hit = toFusedHit(e, specCount, ctx);
            if (hit != null) out.add(hit);
        }
        return out;
    }

    /** One fused hit for {@link #hybridSearchDetailed}; null when the chunk is filtered out. */
    private SearchHit toFusedHit(Map.Entry<String, Double> e, Map<String, Integer> specCount,
                                 FusedHitContext ctx) throws SQLException {
        String prefixedId = e.getKey();
        ChunkMeta meta = chunkMeta().get(prefixedId);
        if (meta == null) return null;

        // Per-spec diversity cap
        if (specCount.merge(meta.specId(), 1, Integer::sum) > ctx.perSpecCap()) return null;

        // Normalize: cap at 1.0 (boosted items can exceed raw maxRrf)
        double normalized = Math.min(1.0, e.getValue() / ctx.maxRrf());

        // Score threshold: drop results below noise floor
        if (normalized < props.minResultScore()) return null;

        Connection conn = connections().get(dbOf(prefixedId));
        try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_CHUNK_TEXT)) {
            ps.setString(1, realId(prefixedId));
            try (ResultSet rs = ps.executeQuery()) {
                String text = rs.next() ? rs.getString("text") : "";
                if (looksBinary(text)) return null;
                // Stub suppression: drop registered-but-not-really-ingested specs
                // unless the user named the spec explicitly. See isStubSpec().
                if (isStubSpec(meta.specId(), text) &&
                        !queryMentionsSpec(ctx.originalQuery(), meta.specId())) {
                    return null;
                }
                double rounded = Math.round(normalized * SCORE_ROUND_FACTOR) / SCORE_ROUND_FACTOR;
                int support = (ctx.denseTopSpecs().contains(meta.specId()) ? 1 : 0)
                            + (ctx.bm25TopSpecs().contains(meta.specId())  ? 1 : 0);
                return new SearchHit(rounded, meta.specId(), meta.release(),
                        meta.title(), meta.seriesDesc(), text == null ? "" : text,
                        prefixedId, meta.docType(), support, meta.chunkIndex());
            }
        }
    }

    /** Stage-2 reranking: re-apply discounts to reranker output, sort, apply final cap. */
    private HybridResult rerankAndCap(String originalQuery, List<SearchHit> pool, int topK,
                                      Integer maxPerSpecOverride, String release,
                                      double extrasWeight, double studyReportDiscount) {
        List<SearchHit> reranked = rerankService.rerank(originalQuery, pool, pool.size());
        List<SearchHit> adjusted = new ArrayList<>(reranked.size());
        Map<String, String> docTypes = atomicDocTypeBySpecId.get();
        // Release implied by the query text ("Rel-18", "Release 18"), used only
        // when the caller did NOT pass an explicit release filter — with a hard
        // filter every surviving hit already matches, so a boost would be a no-op.
        String impliedRelease = (release == null || release.isBlank())
                ? releaseFromQuery(originalQuery) : null;
        for (SearchHit h : reranked) {
            adjusted.add(adjustRerankedScore(h, docTypes, impliedRelease,
                    extrasWeight, studyReportDiscount));
        }
        adjusted.sort((a, b) -> Double.compare(b.score(), a.score()));

        // Final per-spec cap (post-rerank): prevent duplicate-spec runs in output.
        // The pre-rerank pool used props.maxRerankPerSpec to give the reranker more
        // candidates per spec to choose from; the final output uses the tighter cap.
        int finalCap = maxPerSpecOverride != null
                ? Math.max(1, maxPerSpecOverride)
                : props.maxHybridPerSpec();
        return applyFinalPerSpecCap(adjusted, topK, finalCap);
    }

    private SearchHit adjustRerankedScore(SearchHit h, Map<String, String> docTypes,
                                          String impliedRelease,
                                          double extrasWeight, double studyReportDiscount) {
        double s = h.score();
        String hitDocType = docTypes == null ? null : docTypes.get(h.specId());
        if (isStudyReportByDocType(hitDocType, h.specId())
                || isStudyReportByTitle(h.title())) s *= studyReportDiscount;
        if (!isThreeGppSpecId(h.specId()))                  s *= extrasWeight;
        if (impliedRelease != null) {
            s *= impliedRelease.equalsIgnoreCase(h.release())
                    ? props.releaseMatchBoost()
                    : props.releaseMismatchDiscount();
        }
        return h.withScore(Math.round(s * SCORE_ROUND_FACTOR) / SCORE_ROUND_FACTOR);
    }

    /**
     * Applies the final per-spec cap to an already-scored, already-sorted
     * (descending) hit list, and records what the cap actually cut.
     *
     * <p>Pure/static and DB-free by design: every chunk in {@code sortedHits}
     * already cleared the score floor and the reranker, so the ONLY reason one
     * gets excluded here is the spec's quota being full — that is exactly the
     * "cap hid something" case {@link CapDrop} exists to report, as opposed to
     * a low-score drop earlier in the pipeline.
     */
    static HybridResult applyFinalPerSpecCap(List<SearchHit> sortedHits, int topK, int finalCap) {
        Map<String, Integer> finalSpecCount = new HashMap<>();
        Map<String, CapDrop> capDrops = new LinkedHashMap<>();
        List<SearchHit> capped = new ArrayList<>(Math.min(topK, sortedHits.size()));
        for (SearchHit h : sortedHits) {
            if (capped.size() >= topK) break;
            if (finalSpecCount.merge(h.specId(), 1, Integer::sum) > finalCap) {
                capDrops.merge(h.specId(), new CapDrop(h.specId(), 1, h.score()),
                        (prev, now) -> new CapDrop(h.specId(), prev.droppedCount() + 1,
                                Math.max(prev.droppedTopScore(), now.droppedTopScore())));
            } else {
                capped.add(h);
            }
        }
        return new HybridResult(capped, capDrops);
    }

    // ── Adjacent-chunk context ────────────────────────────────────────────────

    /**
     * Short previews of the chunks immediately before/after a hit in the same
     * spec, keyed by document order (chunk_index), not retrieval rank.
     *
     * <p>Why this exists: chunking splits a procedure's prose at a fixed
     * boundary that has nothing to do with sentence or clause structure — "the
     * UE shall ... [chunk boundary] ... perform measurement reporting ...
     * [chunk boundary] ... if T304 expires". Dense/BM25/rerank all score the
     * HIT chunk against the query; none of them look at what sits on either
     * side of it. When the sentence that completes a hit's thought is one
     * chunk index away, the caller never sees it unless it happens to also
     * score well on its own — which prose that says "if it expires" (no timer
     * name repeated) usually does not.
     *
     * <p>Deliberately previews rather than full text: this runs once per
     * returned hit (bounded by topK), and the goal is a continuity hint the
     * caller can act on, not a second copy of the corpus. getSpecInfo remains
     * the way to pull the full neighboring chunk.
     */
    public record AdjacentContext(String before, String after) {
        public boolean isEmpty() { return before == null && after == null; }
    }

    public AdjacentContext adjacentContext(SearchHit hit, int previewChars) throws SQLException {
        if (hit.chunkIndex() < 0) return new AdjacentContext(null, null);
        Map<String, NavigableMap<Integer, String>> idx = atomicSpecChunkIndex.get();
        NavigableMap<Integer, String> bySpec = idx == null ? null : idx.get(hit.specId());
        if (bySpec == null) return new AdjacentContext(null, null);
        String beforeId = bySpec.get(hit.chunkIndex() - 1);
        String afterId  = bySpec.get(hit.chunkIndex() + 1);
        String before = beforeId == null ? null : tailOf(fetchChunkText(beforeId), previewChars);
        String after  = afterId  == null ? null : headOf(fetchChunkText(afterId), previewChars);
        return new AdjacentContext(before, after);
    }

    private String fetchChunkText(String prefixedId) throws SQLException {
        Connection conn = connections().get(dbOf(prefixedId));
        try (PreparedStatement ps = conn.prepareStatement(SQL_SELECT_CHUNK_TEXT)) {
            ps.setString(1, realId(prefixedId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String text = rs.getString("text");
                return looksBinary(text) ? null : text;
            }
        }
    }

    private static String tailOf(String text, int maxChars) {
        if (text == null) return null;
        String t = text.replaceAll("\\s+", " ").strip();
        if (t.isEmpty()) return null;
        return t.length() <= maxChars ? t : t.substring(t.length() - maxChars);
    }

    private static String headOf(String text, int maxChars) {
        if (text == null) return null;
        String t = text.replaceAll("\\s+", " ").strip();
        if (t.isEmpty()) return null;
        return t.length() <= maxChars ? t : t.substring(0, maxChars);
    }

    /** Distinct spec IDs among a retriever's own top-N chunks. */
    private Set<String> topSpecs(List<ScoredId> ranked, int topN) {
        Map<String, ChunkMeta> metaMap = chunkMeta();
        Set<String> out = new HashSet<>();
        if (metaMap == null) return out;
        int n = Math.min(topN, ranked.size());
        for (int i = 0; i < n; i++) {
            ChunkMeta m = metaMap.get(ranked.get(i).chunkId());
            if (m != null) out.add(m.specId());
        }
        return out;
    }

    private static final Pattern THREE_GPP_SPEC_RE = Pattern.compile("^\\d+\\.\\d+(-\\d+)?$");

    private static boolean isThreeGppSpecId(String specId) {
        return specId != null && THREE_GPP_SPEC_RE.matcher(specId).matches();
    }

    /** "Rel-18", "Release 18", "rel 18" → "Rel-18"; null when the query names none. */
    private static final Pattern RELEASE_IN_QUERY_RE =
            Pattern.compile("\\b(?:rel|release)[\\s-]*(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);

    static String releaseFromQuery(String query) {
        if (query == null) return null;
        var m = RELEASE_IN_QUERY_RE.matcher(query);
        return m.find() ? "Rel-" + m.group(1) : null;
    }

    /**
     * Retrieval confidence for a result list, so the orchestrator can decide
     * between trusting these hits and falling back to WebSearch.
     *
     * Calibrated against the 100-question benchmark (2026-07-27). The absolute top
     * score turned out NOT to separate correct from incorrect retrievals — median
     * 0.803 when top-1 was right vs 0.769 when it was wrong. The rank1−rank2
     * margin does separate them:
     *     top-1 correct            → median margin 0.161
     *     top-1 wrong, in top-5    → median margin 0.080
     *     nothing relevant at all  → median margin 0.038
     *
     * A threshold sweep over the 98 scored questions put the useful cut at
     * margin ≥ 0.12: top-1 is correct 84% of the time above it and 51% below,
     * covering 44% of queries. An intermediate "medium" band was tried and
     * dropped — it did not rank between the other two (46% vs 55%), so the gate
     * is deliberately binary. The top score is reported for context only; do not
     * gate on it.
     */
    public RetrievalConfidence confidenceOf(List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return new RetrievalConfidence("low", 0.0, 0.0, 0, 0);
        }
        double top = hits.get(0).score();
        double margin = hits.size() > 1 ? top - hits.get(1).score() : top;
        long specs = hits.stream().map(SearchHit::specId).distinct().count();
        int support = hits.get(0).retrieverSupport();
        // Two signals, combined where they are actually complementary.
        //
        // Margin measures separation within one already-fused ranking. Retriever
        // support measures whether dense and BM25 arrived at the same spec
        // independently. Measured on the 98-question benchmark (2026-07-27):
        //
        //   support alone      0/2 → 40%   1/2 → 51%   2/2 → 83%
        //   margin >= 0.12                              → 88%  (n=42)
        //   margin <  0.12 AND support < 2/2            → 27%  (n=22)
        //   otherwise (margin < 0.12 but both agree)    → 81%  (n=34)
        //
        // The value is in the NEGATIVE conjunction: a weak margin on its own was
        // only a 51% warning, which is barely actionable. A weak margin AND no
        // cross-retriever agreement is a 27% warning, which is. An earlier binary
        // gate collapsed the middle band into "low" and understated 34 good results.
        String level;
        // Degenerate result: everything tied, at the floor. Both retrievers "agree"
        // here only because both returned noise, so the agreement signal is
        // meaningless and the medium tier over-claims badly. Measured on a real
        // failure — a 5 KB JSON blob sent as one query scored top=0.150,
        // margin=0.002 and was reported as "medium (~81% right)".
        //
        // Neither signal alone justifies this: 12 of 100 well-formed questions have
        // top < 0.30 and 9 of those are TOP1-correct, so a low score is not failure.
        // The CONJUNCTION is what discriminates — top < 0.25 AND margin < 0.02 fires
        // on exactly 1 of the 100 well-formed questions, and that one was already a
        // miss.
        if (top < props.confidenceNoneTopScore() && margin < props.confidenceNoneMargin()) {
            level = "none";
        } else if (margin >= props.confidenceHighMargin()) {
            level = "high";
        } else if (support >= FULL_RETRIEVER_SUPPORT) {
            level = "medium";
        } else {
            level = "low";
        }
        return new RetrievalConfidence(level,
                Math.round(margin * CONFIDENCE_ROUND_FACTOR) / CONFIDENCE_ROUND_FACTOR,
                Math.round(top * CONFIDENCE_ROUND_FACTOR) / CONFIDENCE_ROUND_FACTOR,
                (int) specs, support);
    }

    /**
     * Confidence summary attached to a search response.
     *
     * @param support how many retrievers independently ranked the top hit's spec
     *        in their own top tier (0-2)
     */
    public record RetrievalConfidence(String level, double margin, double topScore,
                                      int distinctSpecs, int support) {}

    // ── BM25 / FTS5 ───────────────────────────────────────────────────────────

    /**
     * AND/NEAR-first BM25 retrieval with OR fallback.
     *
     * AND source = original terms + digit-containing expansion tokens only.
     *   - Digit tokens (e.g. "4g" from "LTE → Long Term Evolution 4G") are highly
     *     discriminative and HELP the AND query (e.g. they prevent a non-LTE spec from
     *     winning the "mac harq" query).
     *   - Long English words ("Multimedia", "Subsystem", "Transmission") are noisy and
     *     reduce AND recall without adding precision — kept for OR only.
     * OR source = full expandedQuery for recall when AND is too narrow.
     */
    private List<ScoredId> bm25TopK(String originalQuery, String expandedQuery, int k,
                                     String series, String release, String docType) {
        Set<String> pinned = andSubstTargets(originalQuery);
        FtsQueries queries = buildFtsQueries(originalQuery, expandedQuery, pinned);

        if (queries.andFts().isBlank() && queries.orFts().isBlank()) return List.of();

        boolean hasFilter = (series  != null && !series.isBlank())
                         || (release != null && !release.isBlank())
                         || (docType != null && !docType.isBlank());
        // Oversample when filtering so enough candidates survive the in-memory filter.
        int fetch = hasFilter ? Math.min(k * FILTER_OVERSAMPLE_FACTOR, props.maxHybridCandidates()) : k;

        Map<String, ChunkMeta> metaMap = chunkMeta();
        List<ScoredId> all = new ArrayList<>();

        for (int dbIdx = 0; dbIdx < connections().size(); dbIdx++) {
            all.addAll(bm25ForDb(dbIdx, queries, fetch, originalQuery, series));
        }

        // Apply series / release / doc-type filters in memory (avoids a JOIN on the FTS5 table)
        if (hasFilter && metaMap != null) {
            all = all.stream()
                    .filter(s -> {
                        ChunkMeta m = metaMap.get(s.chunkId());
                        return m != null
                                && filterMatches(series,  m.series())
                                && filterMatches(release, m.release())
                                && filterMatches(docType, m.docType());
                    })
                    .collect(Collectors.toList());
        }

        all.sort((a, b) -> Float.compare(b.score(), a.score()));
        return all.size() > k ? all.subList(0, k) : all;
    }

    /**
     * AND source assembly: substituted original terms + digit expansions +
     * short hyphenated expansions (see {@link #bm25TopK}'s javadoc).
     */
    private String buildAndSource(String originalQuery, String expandedQuery) {
        // Apply AND-path term substitutions before building the AND query.
        // E.g. "volte" → "mmtel" because 23.228 uses "MMTel" not "VoLTE" in its text.
        Set<String> origTermSet = new HashSet<>(extractFtsTerms(originalQuery));
        String substitutedOriginal = applyAndTermSubst(originalQuery);

        // Digit-only expansion terms (e.g. "4g" from LTE) are highly discriminative.
        // Skip them when the original query already has ≥4 short (≤6 char) non-digit
        // non-hyphen terms: those queries are already specific enough, and adding "4g"
        // would exclude IMS specs (23.228) that pre-date the "4G" label.
        long shortOrigTerms = origTermSet.stream()
                .filter(t -> !t.chars().anyMatch(Character::isDigit) && !t.contains("-")
                        && t.length() <= SHORT_TERM_MAX_LENGTH)
                .count();
        String digitExpansions = "";
        if (shortOrigTerms < SPECIFIC_QUERY_SHORT_TERM_COUNT) {
            digitExpansions = extractFtsTerms(expandedQuery).stream()
                    .filter(t -> t.chars().anyMatch(Character::isDigit) && !origTermSet.contains(t))
                    .collect(Collectors.joining(" "));
        }
        // Short hyphenated expansion terms from vocab (e.g. "p-cscf" from VOLTE→"...p-cscf s-cscf")
        // are highly specific (score=1 in termSpecificity, highest priority) and help narrow the
        // AND query to the correct spec when the original query lacks such discriminators.
        // Length cap ≤6 prevents noise from long compound forms like "non-access" (from NAS) or
        // "multi-user" (from MU-MIMO) which would over-constrain the AND predicate.
        String hyphenatedExpansions = extractFtsTerms(expandedQuery).stream()
                .filter(t -> t.contains("-") && t.length() <= SHORT_TERM_MAX_LENGTH
                        && !origTermSet.contains(t))
                .collect(Collectors.joining(" "));
        return Stream.of(substitutedOriginal, digitExpansions, hyphenatedExpansions)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(" "));
    }

    /** The four FTS query strings one BM25 retrieval works through, in order. */
    /**
     * Assembles the four FTS5 query strings tried in order by {@link #bm25ForDb}.
     */
    private FtsQueries buildFtsQueries(String originalQuery, String expandedQuery, Set<String> pinned) {
        String andSource = buildAndSource(originalQuery, expandedQuery);
        int andTermLimit = props.andTermLimit();
        String andFts = buildAndFtsQuery(andSource, andTermLimit, pinned);
        // Progressive relaxation: if the full AND returns nothing, try with one fewer term.
        // Example: Q38 "VoLTE IMS bearer" → AND("p-cscf","s-cscf","ims","lte") = 0 hits in 23.228,
        // but AND("p-cscf","s-cscf","ims") = hits. The relaxed query surfaces 23.228 without
        // falling all the way back to OR (which lets conformance-test specs win via IDF).
        String andFtsRelaxed = andFts.isBlank() ? "" : buildAndFtsQuery(andSource, andTermLimit - 1, pinned);
        // Last relaxation step, only when a vendor-alias substitution fired: AND over
        // the canonical IE terms alone. A CM audit question is mostly filler —
        // "what is the allowed range for siPeriodicity" — and spec text never puts
        // "allowed"/"range"/"golden value" in the same chunk as the IE, so both AND
        // steps above return nothing and the query drops to OR, where the vendor
        // spelling contributes no signal at all. Measured: AND("si-periodicity") = 20
        // chunks, AND("si-periodicity","lte","range","allowed") = 0. Empty when no
        // substitution fired, so queries that never touch the alias table are
        // unaffected.
        String andFtsPinned = pinned.isEmpty()
                ? ""
                : buildAndFtsQuery(String.join(" ", pinned), pinned.size(), pinned);
        // The OR fallback is built from the glossary-expanded ORIGINAL query, which
        // still carries the vendor spelling (0 corpus hits). Append the canonical
        // terms so the IE name — a rare, high-IDF token — can still rank chunks if
        // every AND step misses. No-op when no substitution fired.
        String orSource = pinned.isEmpty()
                ? expandedQuery
                : expandedQuery + " " + String.join(" ", pinned);
        return new FtsQueries(andFts, andFtsRelaxed, andFtsPinned, buildOrFtsQuery(orSource));
    }

    private record FtsQueries(String andFts, String andFtsRelaxed, String andFtsPinned, String orFts) {}

    /** BM25 retrieval against one DB: AND → relaxed AND → pinned AND → (conditional) OR. */
    private List<ScoredId> bm25ForDb(int dbIdx, FtsQueries q, int fetch,
                                     String originalQuery, String series) {
        Connection conn = connections().get(dbIdx);
        List<ScoredId> results = List.of();

        if (!q.andFts().isBlank()) {
            results = bm25TopKFromConn(conn, dbIdx, q.andFts(), fetch);
            // Progressive relaxation: AND(limit) returned nothing → try AND(limit-1).
            // Handles cases like Q38 where the target spec lacks one AND term per-chunk
            // (e.g. 23.228 has mmtel+ims+lte together but not with "voice" in one chunk).
            if (results.isEmpty() && !q.andFtsRelaxed().isBlank()) {
                results = bm25TopKFromConn(conn, dbIdx, q.andFtsRelaxed(), fetch);
            }
            // Vendor-alias floor: the canonical IE terms on their own.
            if (results.isEmpty() && !q.andFtsPinned().isBlank()) {
                results = bm25TopKFromConn(conn, dbIdx, q.andFtsPinned(), fetch);
            }
        }
        // Fall back to OR only when both AND queries return nothing.
        // Extras DB (dbIdx > 0) gets OR fallback ONLY when the query has explicit
        // non-3GPP intent (ospf, bfd, rfc, ietf, etc.) — this allows RFC 5880 to
        // be retrieved via OR when its chunks lack multi-term co-occurrence required
        // by AND. Without this, OSPF/BFD queries never surface RFC 5880 via BM25.
        boolean extrasOrAllowed = dbIdx > 0
                && extrasDbWeightFor(originalQuery, series) >= props.extrasDbNeutralWeight();
        if (results.isEmpty() && !q.orFts().isBlank() && (dbIdx == 0 || extrasOrAllowed)) {
            results = bm25TopKFromConn(conn, dbIdx, q.orFts(), fetch);
        }
        return results;
    }

    /** True when the filter is unset (null/blank) or equals the chunk's value. */
    private static boolean filterMatches(String filter, String value) {
        return filter == null || filter.isBlank() || filter.equals(value);
    }

    /**
     * AND query built from the most "specific" terms in the query.
     * Specificity order: digit-containing (5qi, 5g) > hyphenated (s-nssai, cu-cp) >
     * short ≤6 chars (nssai, rach, qos) > medium ≤9 chars > long common English words.
     *
     * This prioritisation prevents glossary-expansion terms like "information" or
     * "assistance" from crowding out discriminative acronyms in the AND predicate.
     */
    String buildAndFtsQuery(String query, int baseLimit) {
        return buildAndFtsQuery(query, baseLimit, Set.of());
    }

    /**
     * @param pinned terms produced by and-term-subst that must never be truncated
     *               away. A substitution target IS the thing the user asked about
     *               (e.g. "rootSeqIndex" → "rootsequenceindex"), but the length
     *               tiers in termSpecificity rank anything over 9 chars last, so
     *               without pinning a canonical IE name loses its slot to generic
     *               words like "value" or "golden" and the alias silently no-ops.
     */
    String buildAndFtsQuery(String query, int baseLimit, Set<String> pinned) {
        List<String> terms = extractFtsTerms(query);
        if (terms.isEmpty()) return "";
        if (terms.size() == 1) return "{text series_desc}:\"" + terms.get(0) + '"';

        List<String> sorted = terms.stream()
                .sorted(Comparator.comparingInt((String t) -> pinned.contains(t) ? 0 : 1)
                        .thenComparingInt(KbDataService::termSpecificity)
                        .thenComparingInt(String::length)
                        .thenComparing(Comparator.naturalOrder()))
                .toList();

        // Expand limit when digit-containing terms (n1, n2, 5g …) exceed the base limit.
        // These terms all share specificity score 0 and are often tied in length, so the
        // first baseLimit entries may omit a crucial disambiguator (e.g. "5g" after
        // "n1 n2 n3 n4"). Including all digit terms (capped at 6) avoids that loss.
        int digitTerms = (int) sorted.stream()
                .filter(t -> t.chars().anyMatch(Character::isDigit)).count();
        int limit = digitTerms > baseLimit ? Math.min(digitTerms, MAX_DIGIT_AND_TERMS) : baseLimit;

        // Scope AND terms to text+series_desc columns only (exclude title).
        // Including the title column gives specs with technology-branded titles
        // (e.g. "5G NR; NRM...") an unfair BM25 boost for generic 5G/NR queries.
        // series_desc is kept because it correctly identifies LTE specs
        // (series_desc="LTE / E-UTRAN (4G)") for queries about "lte" terms.
        return sorted.stream()
                .limit(limit)
                .map(t -> "{text series_desc}:\"" + t + '"')
                .collect(Collectors.joining(" AND "));
    }

    /** Lower score = higher priority in AND query construction. */
    private static int termSpecificity(String term) {
        if (term.chars().anyMatch(Character::isDigit)) return 0; // 5qi, 5g, e1, n2, f1-u
        if (term.contains("-"))                         return 1; // s-nssai, cu-cp, gtp-u
        if (term.length() <= SHORT_TERM_MAX_LENGTH)     return SPECIFICITY_SHORT;  // nssai, rach, pdcp, qos
        if (term.length() <= MEDIUM_TERM_MAX_LENGTH)    return SPECIFICITY_MEDIUM; // slicing, bearer, handover
        return SPECIFICITY_LONG;                                   // information, assistance (expansion noise)
    }

    /** OR fallback: any matching term qualifies a chunk. */
    String buildOrFtsQuery(String query) {
        List<String> terms = extractFtsTerms(query);
        if (terms.isEmpty()) return "";
        return terms.stream()
                .map(t -> '"' + t + '"')
                .collect(Collectors.joining(" OR "));
    }

    /**
     * Rewrite query tokens to their canonical 3GPP spelling before the AND
     * predicate is built. Two cases, both driven by and-term-subst.tsv:
     *   - synonym drift: "volte" → "mmtel" (23.228 says MMTel, never VoLTE)
     *   - vendor CM attribute names: "siPeriodicity" → "si-periodicity",
     *     the 36.331 IE. The vendor camelCase form has zero corpus hits, so
     *     without this the AND path cannot reach the clause at all.
     *
     * Tokens are cleaned exactly as extractFtsTerms() cleans them, so the map
     * keys stay in one form (lower-case, alphanumeric plus hyphen). Unmapped
     * tokens pass through unchanged.
     *
     * Package-private so the substitution table is regression-testable without
     * opening a database.
     */
    String applyAndTermSubst(String query) {
        if (query == null || query.isBlank()) return "";
        Map<String, String> subst = lexicon.andTermSubst();
        return Arrays.stream(query.split("\\s+"))
                .map(tok -> {
                    String t = tok.replaceAll(NON_TERM_CHARS_RE, "").toLowerCase();
                    return subst.getOrDefault(t, t);
                })
                .collect(Collectors.joining(" "));
    }

    /**
     * The canonical terms that applyAndTermSubst() actually produced for this
     * query. Passed to buildAndFtsQuery() as the pinned set so a substituted IE
     * name cannot be truncated out of the AND predicate.
     */
    Set<String> andSubstTargets(String query) {
        if (query == null || query.isBlank()) return Set.of();
        Map<String, String> subst = lexicon.andTermSubst();
        Set<String> out = new LinkedHashSet<>();
        for (String tok : query.split("\\s+")) {
            String t = tok.replaceAll(NON_TERM_CHARS_RE, "").toLowerCase();
            String canonical = subst.get(t);
            if (canonical != null) out.addAll(extractFtsTerms(canonical));
        }
        return out;
    }

    List<String> extractFtsTerms(String query) {
        if (query == null) return List.of();
        Set<String> stopWords = lexicon.stopWords();
        return Arrays.stream(query.split("\\s+"))
                .map(t -> t.replaceAll(NON_TERM_CHARS_RE, "").toLowerCase())
                .filter(t -> t.length() >= MIN_FTS_TERM_LENGTH && !stopWords.contains(t))
                .distinct()
                .toList();
    }

    /**
     * True if this chunk's spec is a TR / study report.
     * Source of truth is the DB doc_type column (set to 'TR' by the ingestion
     * pipeline or by the one-shot DB patch). When doc_type is missing or
     * stale we fall back to the spec_id numeric range
     * [props.studyReportRangeStart, props.studyReportRangeEnd).
     */
    boolean isStudyReport(ChunkMeta meta) {
        if (meta == null) return false;
        if ("TR".equalsIgnoreCase(meta.docType())) return true;
        return isStudyReportByPattern(meta.specId())
                || isStudyReportByTitle(meta.title());
    }

    /** Path used post-rerank where only specId is in hand; doc_type pre-resolved via docTypeBySpecId. */
    boolean isStudyReportByDocType(String docType, String specId) {
        if ("TR".equalsIgnoreCase(docType)) return true;
        return isStudyReportByPattern(specId);
    }

    /**
     * Third study-report signal, from the recovered cover-page title.
     *
     * Needed because the other two both miss a real class of documents: 11 specs
     * (1796 chunks) are titled "Study on …" / "Technical report on …" yet carry
     * doc_type='TS' in the DB AND fall outside the 700–899 numeric convention —
     * 38.900, 38.912, 38.913, 23.9xx. 38.912 in particular was outranking 36.331
     * on LTE RRC queries with no discount applied at all.
     *
     * Only usable since cover-page titles were recovered; specs still holding a
     * placeholder title fall through to the other two signals.
     */
    private static final Pattern STUDY_TITLE_RE = Pattern.compile(
            "\\b(?:study\\s+(?:on|of|into)|feasibility\\s+study|technical\\s+report\\b)",
            Pattern.CASE_INSENSITIVE);

    static boolean isStudyReportByTitle(String title) {
        return title != null && STUDY_TITLE_RE.matcher(title).find();
    }

    /** Spec-id range fallback: 700–899 (3GPP convention for study items / TRs). */
    private boolean isStudyReportByPattern(String specId) {
        if (specId == null) return false;
        int dotIdx = specId.indexOf('.');
        if (dotIdx < 0) return false;
        String afterDot = specId.substring(dotIdx + 1);
        int dashIdx = afterDot.indexOf('-');
        String numStr = dashIdx >= 0 ? afterDot.substring(0, dashIdx) : afterDot;
        try {
            int n = Integer.parseInt(numStr);
            return n >= props.studyReportRangeStart() && n < props.studyReportRangeEnd();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Decide the multiplier applied to extras-DB chunks for THIS query.
     * Returns extras-db neutral weight when:
     *   - A series filter is set (caller has scoped the search)
     *   - Query contains any NON_3GPP_INTENT_TERMS marker (loaded from classpath)
     * Returns extras-db discount otherwise.
     */
    double extrasDbWeightFor(String originalQuery, String series) {
        if (series != null && !series.isBlank()) {
            return props.extrasDbNeutralWeight();
        }
        if (originalQuery == null || originalQuery.isBlank()) {
            return props.extrasDbDiscount();
        }
        String q = originalQuery.toLowerCase();
        for (String term : lexicon.non3gppIntentTerms()) {
            // word-boundary match for short terms to avoid e.g. "ip" matching inside "description"
            if (term.length() <= SHORT_INTENT_TERM_MAX_LENGTH) {
                if (containsWord(q, term)) return props.extrasDbNeutralWeight();
            } else {
                if (q.contains(term)) return props.extrasDbNeutralWeight();
            }
        }
        return props.extrasDbDiscount();
    }

    private static boolean containsWord(String haystack, String needle) {
        // Cheap word-boundary check: needle preceded and followed by non-letter
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            boolean leftOk  = (idx == 0) || !Character.isLetterOrDigit(haystack.charAt(idx - 1));
            int end = idx + needle.length();
            boolean rightOk = (end == haystack.length()) || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            idx = end;
        }
        return false;
    }

    private List<ScoredId> bm25TopKFromConn(Connection conn, int dbIdx, String fts, int k) {
        // Query the content FTS5 table directly — no JOIN needed.
        // The 'id' column is UNINDEXED in chunks_fts and fetched from the content table (chunks).
        // Series/release/docType filters are applied in-memory by the caller.
        String sql = "SELECT id, bm25(chunks_fts) AS score FROM chunks_fts " +
                     "WHERE chunks_fts MATCH ? ORDER BY score LIMIT ?";
        List<ScoredId> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fts);
            ps.setObject(SECOND_SQL_PARAM, k);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    if (id == null) continue;
                    // SQLite bm25() is negative (lower = better); flip to positive for RRF
                    out.add(new ScoredId(dbIdx + ":" + id, -rs.getFloat("score")));
                }
            }
        } catch (SQLException e) {
            log.warn("BM25 query failed for '{}': {}", fts, e.getMessage());
        }
        return out;
    }

    // ── Dense vector search ───────────────────────────────────────────────────

    /**
     * Cosine top-K with per-spec cap.
     * The all-MiniLM-L6-v2 model was trained on general text, so domain-specific
     * chunks from the same popular spec tend to cluster together in embedding space.
     * Capping at MAX_DENSE_PER_SPEC forces diversity in the candidate pool before
     * RRF fusion, giving BM25 a fairer chance to surface other relevant specs.
     */
    private List<ScoredId> cosineTopK(float[] qvec, int k,
                                       String series, String release, String docType) {
        String[]              ids       = atomicChunkIds.get();
        float[]               embeddings = atomicEmbeddings.get();
        Map<String, ChunkMeta> metaMap  = chunkMeta();
        if (ids == null || embeddings == null || metaMap == null) return List.of();

        int maxDensePerSpec = props.maxDensePerSpec();
        List<ScoredId> results = scoreAllChunks(qvec, ids, embeddings, metaMap,
                series, release, docType);
        results.sort((a, b) -> Float.compare(b.score(), a.score()));
        return capPerSpec(results, metaMap, k, maxDensePerSpec);
    }

    /** Full-scan cosine scoring over the in-RAM index, applying the metadata filters. */
    private List<ScoredId> scoreAllChunks(float[] qvec, String[] ids, float[] embeddings,
                                          Map<String, ChunkMeta> metaMap,
                                          String series, String release, String docType) {
        int d = dim();
        List<ScoredId> results = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            String id   = ids[i];
            ChunkMeta m = metaMap.get(id);
            if (m == null
                    || !filterMatches(series,  m.series())
                    || !filterMatches(release, m.release())
                    || !filterMatches(docType, m.docType())) continue;
            int off = i * d;
            float dot = 0f;
            for (int j = 0; j < d; j++) dot += qvec[j] * embeddings[off + j];
            results.add(new ScoredId(id, dot));
        }
        return results;
    }

    /** Per-spec cap: prevents one popular spec dominating the entire candidate pool. */
    private static List<ScoredId> capPerSpec(List<ScoredId> results, Map<String, ChunkMeta> metaMap,
                                             int k, int maxDensePerSpec) {
        Map<String, Integer> specHits = new HashMap<>();
        List<ScoredId> capped = new ArrayList<>(k);
        for (ScoredId s : results) {
            ChunkMeta m = metaMap.get(s.chunkId());
            if (m != null && specHits.merge(m.specId(), 1, Integer::sum) <= maxDensePerSpec) {
                capped.add(s);
                if (capped.size() >= k) break;
            }
        }
        return capped;
    }

    /**
     * Matches an IE's ASN.1 definition in both forms the specs use:
     *   top-level   "T-PollRetransmit ::= ENUMERATED { ms5, ms10, ... }"
     *   inline field "maxRetxThreshold ENUMERATED { t1, t2, ... }" inside a SEQUENCE
     *
     * The "::=" is optional for exactly that reason. Omitting it silently matched
     * only inline fields, so every top-level definition — which is where a
     * standalone IE like T-PollRetransmit or TimeAlignmentTimer actually lives —
     * came back "not found" while sitting in the index.
     */
    private static Pattern ieDefinitionPattern(String ie) {
        return Pattern.compile(
                "\\b(" + Pattern.quote(ie) + "(?:-[A-Za-z0-9]+)*)\\s*(?:::=)?\\s*"
                        + "(ENUMERATED\\s*\\{[^}]{0,300}\\}"
                        + "|INTEGER\\s*\\([^)]{0,80}\\)"
                        + "|BIT\\s+STRING[^,;}]{0,80})",
                Pattern.CASE_INSENSITIVE);
    }

    /** One ASN.1 definition of an information element, verbatim from the spec. */
    public record IeDefinition(String specId, String ieName, String definition,
                               String release, String context) {}

    /**
     * Exact-pattern lookup of an information element's ASN.1 definition.
     *
     * <p>Why this exists alongside hybridSearch: an IE definition is a LEXICAL
     * pattern ({@code <IE> ENUMERATED {...}} / {@code <IE> INTEGER (a..b)}), not a
     * semantic one. Dense retrieval and a cross-encoder are the wrong instruments —
     * measured on a 30-parameter Bulk-CM audit, the semantic pipeline surfaced the
     * permitted values for 4 parameters while this pattern scan finds 16, because
     * ASN.1 blocks are symbol soup that a cross-encoder ranks below prose merely
     * mentioning the same IE.
     *
     * <p>Returns every release variant found (-r13, -r14, …) rather than guessing:
     * si-Periodicity has different enumerations for NB-IoT and MBMS, and only the
     * caller's managed-object context can say which applies.
     */
    /** Whether the finer clause-level index has been built (see build_clause_index.py). */
    private volatile Boolean clauseIndexPresent;

    private boolean hasClauseIndex() {
        Boolean cached = clauseIndexPresent;
        if (cached != null) return cached;
        boolean present = false;
        for (Connection conn : connections()) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='clauses'")) {
                if (rs.next()) { present = true; break; }
            } catch (SQLException e) {
                log.debug("clause-index probe failed: {}", e.getMessage());
            }
        }
        log.info("clause-level index {}", present ? "present — IE lookup will use it"
                : "absent — IE lookup falls back to 400-word chunks");
        clauseIndexPresent = present;
        return present;
    }

    public List<IeDefinition> lookupIeDefinition(String ieName, String series, int limit)
            throws SQLException {
        if (ieName == null || ieName.isBlank()) return List.of();
        String ie = ieName.trim();

        // Prefer the clause-level index when it exists. Units there are one IE
        // each and capped below the reranker window, so the definition is the
        // whole unit rather than one line inside a 3000-character block.
        if (hasClauseIndex()) {
            List<IeDefinition> fine = lookupInClauses(ie, series, limit);
            if (!fine.isEmpty()) return fine;
        }
        Pattern rx = ieDefinitionPattern(ie);

        List<IeDefinition> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Connection conn : connections()) {
            scanChunksForIe(conn, ie, series, rx, limit, out, seen);
        }
        return out;
    }

    /** Scan one DB's chunks table for ASN.1 definitions of {@code ie}. */
    private void scanChunksForIe(Connection conn, String ie, String series, Pattern rx,
                                 int limit, List<IeDefinition> out, Set<String> seen)
            throws SQLException {
        // LIKE prefilter first: full-table regex over 185k chunks would be slow,
        // and the IE name is a rare token so this narrows hard.
        StringBuilder sql = new StringBuilder(
                "SELECT spec_id, release, text FROM chunks WHERE text LIKE ?");
        List<Object> params = new ArrayList<>();
        params.add("%" + ie + "%");
        if (series != null && !series.isBlank()) {
            sql.append(" AND series=?");
            params.add(series);
        }
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && out.size() < limit) {
                    String text = rs.getString("text");
                    if (text == null || looksBinary(text)) continue;
                    collectChunkIeMatches(rx, text, rs, limit, out, seen);
                }
            }
        }
    }

    /** Collect every distinct IE-definition match inside one chunk's text. */
    private void collectChunkIeMatches(Pattern rx, String text, ResultSet rs, int limit,
                                       List<IeDefinition> out, Set<String> seen)
            throws SQLException {
        var m = rx.matcher(text);
        while (m.find() && out.size() < limit) {
            String name = m.group(1);
            String def  = m.group(IE_DEF_GROUP).replaceAll("\\s+", " ");
            String key  = rs.getString(COL_SPEC_ID) + "|" + name + "|" + def;
            if (!seen.add(key)) continue;
            int from = Math.max(0, m.start() - IE_CONTEXT_BEFORE_CHARS);
            String ctx = text.substring(from, Math.min(text.length(), m.end() + IE_CONTEXT_AFTER_CHARS))
                    .replaceAll("\\s+", " ").strip();
            out.add(new IeDefinition(rs.getString(COL_SPEC_ID), name, def,
                    rs.getString(COL_RELEASE), ctx));
        }
    }

    /** IE lookup against the clause-level index; empty when it has no match. */
    private List<IeDefinition> lookupInClauses(String ie, String series, int limit) {
        Pattern rx = ieDefinitionPattern(ie);
        List<IeDefinition> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Connection conn : connections()) {
            try {
                scanClausesForIe(conn, ie, series, rx, limit, out, seen);
            } catch (SQLException e) {
                log.debug("clause lookup failed on one DB: {}", e.getMessage());
            }
        }
        return out;
    }

    /** Scan one DB's clause-level index for ASN.1 definitions of {@code ie}. */
    private void scanClausesForIe(Connection conn, String ie, String series, Pattern rx,
                                  int limit, List<IeDefinition> out, Set<String> seen)
            throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT spec_id, release, clause_id, text FROM clauses WHERE text LIKE ?");
        List<Object> params = new ArrayList<>();
        params.add("%" + ie + "%");
        if (series != null && !series.isBlank()) {
            sql.append(" AND series=?");
            params.add(series);
        }
        // Units whose ie_name IS this IE are the definition itself; rank them first.
        sql.append(" ORDER BY CASE WHEN ie_name LIKE ? THEN 0 ELSE 1 END, length(text)");
        params.add(ie + "%");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && out.size() < limit) {
                    String text = rs.getString("text");
                    if (text == null) continue;
                    collectClauseIeMatches(rx, text, rs, limit, out, seen);
                }
            }
        }
    }

    /** Collect every distinct IE-definition match inside one clause unit's text. */
    private void collectClauseIeMatches(Pattern rx, String text, ResultSet rs, int limit,
                                        List<IeDefinition> out, Set<String> seen)
            throws SQLException {
        var m = rx.matcher(text);
        while (m.find() && out.size() < limit) {
            String def = m.group(IE_DEF_GROUP).replaceAll("\\s+", " ");
            String key = rs.getString(COL_SPEC_ID) + "|" + m.group(1) + "|" + def;
            if (!seen.add(key)) continue;
            out.add(new IeDefinition(rs.getString(COL_SPEC_ID), m.group(1), def,
                    rs.getString(COL_RELEASE),
                    text.replaceAll("\\s+", " ").strip()));
        }
    }

    // ── Spec / list queries ───────────────────────────────────────────────────

    public List<Map<String, Object>> getSpecChunks(String specId, int maxChunks) throws SQLException {
        for (Connection conn : connections()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM chunks WHERE spec_id=? ORDER BY chunk_index LIMIT ?")) {
                ps.setString(1, specId);
                ps.setInt(SECOND_SQL_PARAM, maxChunks);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(rowMap(rs));
                }
            }
            if (!rows.isEmpty()) return rows;
        }
        return List.of();
    }

    /**
     * Chunks of one spec, ranked by relevance to {@code query} instead of by
     * chunk_index.
     *
     * WHY THIS EXISTS
     * The chunk_index ordering above returns the FIRST n chunks, and chunk 0..2
     * of any long document is its cover page and table of contents. Measured:
     * getSpecInfo("RFC3985", 3) returned three chunks of ". . . . 28 6.5.
     * Congestion Considerations"; getSpecInfo("23.205", 5) and
     * getSpecInfo("RFC2328", 5/10/20) did the same. In three separate agent
     * runs the model found the right spec, called getSpecInfo, received the
     * table of contents, and either looped on rephrased queries or gave up —
     * the content it needed was in chunk 40, not chunk 1.
     *
     * Falls back to the chunk_index ordering when the query yields no usable
     * FTS terms or matches nothing, so a caller never gets an empty result
     * where the old behaviour would have returned something.
     */
    public List<Map<String, Object>> getSpecChunks(String specId, int maxChunks, String query)
            throws SQLException {
        if (query == null || query.isBlank()) return getSpecChunks(specId, maxChunks);
        String fts = buildOrFtsQuery(query);
        if (fts.isBlank()) return getSpecChunks(specId, maxChunks);

        for (Connection conn : connections()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT c.* FROM chunks_fts f JOIN chunks c ON c.rowid = f.rowid "
                            + "WHERE f.chunks_fts MATCH ? AND c.spec_id = ? "
                            + "ORDER BY rank LIMIT ?")) {
                ps.setString(1, fts);
                ps.setString(SECOND_SQL_PARAM, specId);
                ps.setInt(THIRD_SQL_PARAM, maxChunks);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(rowMap(rs));
                }
            } catch (SQLException e) {
                // A DB without chunks_fts, or an FTS syntax the tokenizer rejects.
                // Not fatal — the caller still gets the chunk_index ordering.
                log.debug("spec-scoped FTS failed for {} ({}): {}", specId, fts, e.getMessage());
            }
            if (!rows.isEmpty()) return rows;
        }
        return getSpecChunks(specId, maxChunks);
    }

    public List<Map<String, Object>> listSpecs(String series, String release) throws SQLException {
        Map<String, Map<String, Object>> bySpecId = new LinkedHashMap<>();
        for (Connection conn : connections()) {
            for (Map<String, Object> row : listSpecsFromConn(conn, series, release)) {
                bySpecId.put((String) row.get(COL_SPEC_ID), row);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(bySpecId.values());
        out.sort(Comparator.comparing(m -> (String) m.get(COL_SPEC_ID)));
        return out;
    }

    private List<Map<String, Object>> listSpecsFromConn(Connection conn,
                                                         String series, String release) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT spec_id, series, series_desc, release, doc_type, " +
                        "MAX(total_chunks) AS total_chunks FROM chunks");
        List<Object> params = new ArrayList<>();
        List<String> where  = new ArrayList<>();
        if (series  != null && !series.isBlank())  { where.add("series=?");  params.add(series); }
        if (release != null && !release.isBlank()) { where.add("release=?"); params.add(release); }
        if (!where.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", where));
        sql.append(" GROUP BY spec_id ORDER BY spec_id");

        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rowMap(rs));
            }
        }
        return out;
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    /**
     * Every spec_id present in the index. Served from the in-memory chunk
     * metadata, so it is cheap and safe to call on the request path.
     * Used by {@link ScopeGateService} to decide which out-of-scope markers apply.
     */
    public Set<String> allSpecIds() {
        Map<String, Integer> counts = specChunkCounts();
        return counts == null ? Set.of() : Set.copyOf(counts.keySet());
    }

    public Set<String> indexedSeries() throws SQLException {
        Set<String> series = new HashSet<>();
        for (Connection conn : connections()) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT DISTINCT series FROM chunks")) {
                while (rs.next()) series.add(rs.getString("series"));
            }
        }
        return series;
    }

    public long totalChunks() throws SQLException {
        long total = 0;
        for (Connection conn : connections()) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) AS n FROM chunks")) {
                total += rs.next() ? rs.getLong("n") : 0L;
            }
        }
        return total;
    }

    public long totalSpecs() throws SQLException {
        Set<String> specIds = new HashSet<>();
        for (Connection conn : connections()) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT DISTINCT spec_id FROM chunks")) {
                while (rs.next()) specIds.add(rs.getString(COL_SPEC_ID));
            }
        }
        return specIds.size();
    }

    /**
     * Count of specs that have only stub-level content (≤ STUB_MAX_CHUNKS_PER_SPEC).
     * Surfaced through kbStats so the orchestrator can see how much of the
     * indexed corpus is actually substantive vs. registered-but-thin.
     */
    public long stubSpecCount() {
        Map<String, Integer> counts = specChunkCounts();
        if (counts == null) return 0L;
        int max = props.stubMaxChunksPerSpec();
        return counts.values().stream()
                .filter(n -> n <= max)
                .count();
    }

    public String embedModelName() throws SQLException {
        String model = null;
        for (Connection conn : connections()) {
            String m = readMetaValue(conn, "embed_model");
            if (m == null) continue;
            if (model == null) {
                model = m;
            } else if (!model.equalsIgnoreCase(m)) {
                log.warn("embed model mismatch between DBs: '{}' vs '{}'; using '{}'", model, m, model);
            }
        }
        return model;
    }

    /**
     * Read the {@code embed_dim} value the ingestion pipeline wrote into the meta
     * table. Returns 0 when no DB carries the key — caller should treat that as
     * "unknown, trust runtime config".
     */
    public int embedDimFromMeta() throws SQLException {
        int dim = 0;
        for (Connection conn : connections()) {
            String value = readMetaValue(conn, "embed_dim");
            if (value == null) continue;
            try {
                int parsed = Integer.parseInt(value.trim());
                if (dim == 0) {
                    dim = parsed;
                } else if (dim != parsed) {
                    log.warn("embed_dim mismatch between DBs: {} vs {}; using {}", dim, parsed, dim);
                }
            } catch (NumberFormatException e) {
                log.warn("embed_dim in meta is non-numeric: '{}'", value);
            }
        }
        return dim;
    }

    private String readMetaValue(Connection conn, String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value") : null;
            }
        }
    }

    public int vectorCount() {
        String[] ids = atomicChunkIds.get();
        return ids == null ? 0 : ids.length;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /**
     * True if the spec is a "stub" — registered in the index but with so
     * little content that returning it as a confident search hit would
     * mislead the user. Two signals must agree before suppression:
     *   1. The spec has very few chunks (≤ STUB_MAX_CHUNKS_PER_SPEC).
     *   2. THIS chunk's content is short (< STUB_MAX_CHUNK_TEXT_LENGTH).
     *
     * Both conditions matter: a small spec with substantial per-chunk
     * content is genuine (e.g. a one-page recommendation that's actually
     * the whole document); a large spec with one short chunk is a normal
     * cover-page chunk that other chunks back up. Only the case where
     * BOTH the spec is tiny AND this chunk is short is the silent-failure
     * pattern we want to suppress.
     */
    private boolean isStubSpec(String specId, String text) {
        Map<String, Integer> counts = specChunkCounts();
        if (counts == null) return false;
        Integer count = counts.get(specId);
        if (count == null) return false;
        if (count > props.stubMaxChunksPerSpec()) return false;
        int len = text == null ? 0 : text.length();
        return len < props.stubMaxChunkTextLength();
    }

    /**
     * True if the user's query explicitly names this spec by ID.
     * When the user types "X.733 alarm model", we should return the X.733
     * stub even if it would normally be suppressed — the user already
     * knows what they're asking for; surfacing the available metadata is
     * better than silently returning nothing.
     *
     * Matches case-insensitively and tolerates the common variants:
     *   "X.733"      "x733"        "X733"      (with/without dot)
     *   "ITU-T-X.733" / "ITU-T X.733"
     *   "TS 38.331"  / "38.331"    / "38331"
     *   "RFC5880"    / "RFC 5880"
     *
     * Also strips known source prefixes ("itu-t-", "rfc", "tmf") from the
     * spec ID before matching so users can name the spec without the prefix.
     * Example: query "X.733 alarm" should match specId "ITU-T-X.733".
     */
    static boolean queryMentionsSpec(String query, String specId) {
        if (query == null || specId == null) return false;
        String q = normalizeForSpecMatch(query);
        String s = normalizeForSpecMatch(specId);
        if (s.isEmpty()) return false;
        // Try full normalized form first
        if (q.contains(s)) return true;
        // Strip known source prefixes (case after normalization, all lower)
        // "itu-t-x.733" → "itutx733" → "x733"
        // "tmf-642"     → "tmf642"   (keep — unique enough)
        // "oran-wg4-cus"→ "oranwg4cus" (keep)
        for (String prefix : new String[]{"itut", "ietf"}) {
            if (s.startsWith(prefix)) {
                String stripped = s.substring(prefix.length());
                if (!stripped.isEmpty() && q.contains(stripped)) return true;
            }
        }
        return false;
    }

    private static String normalizeForSpecMatch(String s) {
        return s.toLowerCase().replaceAll("[\\s.\\-_]+", "");
    }

    static boolean looksBinary(String text) {
        if (text == null || text.isBlank()) return true;
        String s = text.length() > BINARY_SNIFF_CHARS ? text.substring(0, BINARY_SNIFF_CHARS) : text;
        if (s.contains("EMF+") || s.contains("w:docVar") || s.contains("w:val=")) return true;
        int bad = 0;
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            if ((c < MIN_PRINTABLE_CHAR && c != '\n' && c != '\r' && c != '\t')
                    || c > MAX_PRINTABLE_CHAR) bad++;
        }
        return bad * BINARY_BAD_CHAR_RATIO > s.length();
    }

    private float[] vectorFromBlob(byte[] blob) throws IOException {
        int d = dim();
        if (blob == null || blob.length < d * FLOAT_BYTES) {
            throw new IOException("Invalid embedding vector blob length=" +
                    (blob == null ? "null" : blob.length));
        }
        float[] out = new float[d];
        ByteBuffer bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < d; i++) out[i] = bb.getFloat();
        return out;
    }

    private Map<String, Object> rowMap(ResultSet rs) throws SQLException {
        int cols = rs.getMetaData().getColumnCount();
        Map<String, Object> map = new HashMap<>();
        for (int i = 1; i <= cols; i++) {
            map.put(rs.getMetaData().getColumnName(i), rs.getObject(i));
        }
        return map;
    }

    private record ScoredId(String chunkId, float score) {}
}

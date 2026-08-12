package com.vwaves.mcp.service;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vwaves.mcp.config.RetrievalProperties;
import com.vwaves.mcp.model.SearchHit;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared fixture for the DB-backed {@link KbDataService} retrieval tests.
 *
 * <p>Builds throwaway SQLite files with the exact production schema the
 * service reads: a {@code chunks} table (chunk_index stored as TEXT,
 * total_chunks for listSpecs), an {@code embeddings} table whose vectors are
 * little-endian float32 blobs (the format {@code loadEmbeddings} /
 * {@code vectorFromBlob} expects), and a {@code meta} table for
 * embed_model/embed_dim. All embeddings in these tests are 4-dimensional;
 * the service L2-normalises them at load time, so tests may store unnormalised
 * component vectors and reason purely about cosine ordering.
 *
 * <p>Everything here is copied-style from KbDataServiceAdjacentContextTest's
 * approach (temp SQLite + real init()) rather than sharing code with it, so
 * existing files stay untouched.
 */
final class KbSearchTestSupport {

    static final int DIM = 4;

    private KbSearchTestSupport() {}

    // ── Schema / inserts ──────────────────────────────────────────────────────

    static Connection open(Path db) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
    }

    static void createSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE chunks (" +
                    "id TEXT PRIMARY KEY, spec_id TEXT, release TEXT, series TEXT, " +
                    "series_desc TEXT, doc_type TEXT, title TEXT, chunk_index TEXT, " +
                    "total_chunks INTEGER, text TEXT)");
            st.executeUpdate("CREATE TABLE embeddings (chunk_id TEXT, vector BLOB)");
            st.executeUpdate("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)");
        }
    }

    static void insertChunk(Connection conn, String id, String specId, String release,
                            String series, String seriesDesc, String docType, String title,
                            int chunkIndex, int totalChunks, String text) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO chunks (id, spec_id, release, series, series_desc, doc_type, " +
                        "title, chunk_index, total_chunks, text) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, specId);
            ps.setString(3, release);
            ps.setString(4, series);
            ps.setString(5, seriesDesc);
            ps.setString(6, docType);
            ps.setString(7, title);
            ps.setString(8, Integer.toString(chunkIndex));
            ps.setInt(9, totalChunks);
            ps.setString(10, text);
            ps.executeUpdate();
        }
    }

    /** Chunk + its embedding in one call; the common case. */
    static void insertChunkWithVector(Connection conn, String id, String specId, String release,
                                      String series, String seriesDesc, String docType, String title,
                                      int chunkIndex, int totalChunks, String text, float... vec)
            throws SQLException {
        insertChunk(conn, id, specId, release, series, seriesDesc, docType, title,
                chunkIndex, totalChunks, text);
        insertEmbedding(conn, id, vec);
    }

    static void insertEmbedding(Connection conn, String chunkId, float... vec) throws SQLException {
        insertEmbeddingBlob(conn, chunkId, blob(vec));
    }

    static void insertEmbeddingBlob(Connection conn, String chunkId, byte[] blob) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO embeddings (chunk_id, vector) VALUES (?,?)")) {
            ps.setString(1, chunkId);
            ps.setBytes(2, blob);
            ps.executeUpdate();
        }
    }

    static void insertMeta(Connection conn, String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO meta (key, value) VALUES (?,?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    static void createClausesTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE clauses (spec_id TEXT, release TEXT, series TEXT, " +
                    "clause_id TEXT, ie_name TEXT, text TEXT)");
        }
    }

    static void insertClause(Connection conn, String specId, String release, String series,
                             String clauseId, String ieName, String text) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO clauses (spec_id, release, series, clause_id, ie_name, text) " +
                        "VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, specId);
            ps.setString(2, release);
            ps.setString(3, series);
            ps.setString(4, clauseId);
            ps.setString(5, ieName);
            ps.setString(6, text);
            ps.executeUpdate();
        }
    }

    /** Little-endian float32 blob, the exact on-disk embedding format. */
    static byte[] blob(float... vec) {
        ByteBuffer bb = ByteBuffer.allocate(vec.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : vec) bb.putFloat(f);
        return bb.array();
    }

    static float[] vec(float a, float b, float c, float d) {
        return new float[]{a, b, c, d};
    }

    // ── Collaborators ─────────────────────────────────────────────────────────

    /**
     * Retrieval knobs chosen for unambiguous assertions:
     * maxHybridPerSpec=1 (default diversity cap), maxDensePerSpec=3,
     * rrfK=60 (so maxRrf = 2/61), coOccurrenceBoost configurable (1.0 disables
     * it so RRF normalisation stays below the 1.0 cap and score ordering is
     * exactly computable by hand), releaseMatchBoost=1.2 / mismatch=0.8,
     * extrasDbDiscount=0.5, stub suppression at ≤1 chunk & <100 chars.
     */
    static RetrievalProperties props(double minResultScore, double coOccurrenceBoost,
                                     double studyReportDiscount) {
        return new RetrievalProperties(
                3, 1, 3,
                10, 4, 100, 3, 50,
                minResultScore, coOccurrenceBoost, 60, studyReportDiscount, 0.5, 1.0,
                1.2, 0.8,
                10,
                0.12, 0.25, 0.02,
                1, 100,
                3,
                700, 800,
                "unused", "unused", "unused", "unused",
                0.0, 0.0, "unused", "unused", "unused", "unused", 1.0,
                "unused", "unused", 0.5);
    }

    static RetrievalProperties defaultProps() {
        return props(0.0, 1.0, 0.85);
    }

    /** Fully controlled lexicons; no classpath resources are read. */
    static LexiconService lexicon() {
        LexiconService lex = mock(LexiconService.class);
        when(lex.stopWords()).thenReturn(Set.of(
                "the", "a", "an", "for", "what", "is", "of", "and", "to", "on", "in", "by"));
        when(lex.andTermSubst()).thenReturn(Map.of(
                "volte", "mmtel",
                "siperiodicity", "si-periodicity"));
        when(lex.non3gppIntentTerms()).thenReturn(Set.of("bfd", "ospf", "rfc"));
        return lex;
    }

    /** Reranker that reports not-ready: the pipeline takes the pure-RRF branch. */
    static RerankService rerankOff() {
        RerankService rr = mock(RerankService.class);
        when(rr.isReady()).thenReturn(false);
        return rr;
    }

    /** Ready reranker whose rerank() returns its candidate list unchanged. */
    static RerankService rerankPassthrough() {
        RerankService rr = mock(RerankService.class);
        when(rr.isReady()).thenReturn(true);
        when(rr.rerank(anyString(), anyList(), anyInt()))
                .thenAnswer(inv -> List.copyOf(inv.<List<SearchHit>>getArgument(1)));
        return rr;
    }

    /** dim()=4; embed() is never called by KbDataService, only dim(). */
    static EmbeddingService embedding() {
        return new EmbeddingService(null, "test", DIM);
    }
}

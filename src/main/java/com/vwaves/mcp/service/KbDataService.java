package com.vwaves.mcp.service;

import com.vwaves.mcp.model.ChunkMeta;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class KbDataService {
    private static final Logger log = LoggerFactory.getLogger(KbDataService.class);
    private static final int DIM = 384;

    private volatile float[] allEmbeddings;
    private volatile String[] chunkIds;
    private volatile Map<String, ChunkMeta> chunkMeta;
    private volatile Connection connection;

    public void init(Path dbPath, StartupState startupState) throws SQLException, IOException {
        startupState.phase("loading-db");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        loadEmbeddings();
        loadChunkMeta();
        startupState.phase("building-fts");
        buildFts5IfMissing();
    }

    private void buildFts5IfMissing() throws SQLException {
        boolean exists;
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='chunks_fts'")) {
            exists = rs.next();
        }
        if (exists) {
            try (Statement s = connection.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) AS n FROM chunks_fts")) {
                long n = rs.next() ? rs.getLong("n") : 0;
                log.info("FTS5 index already present ({} rows)", n);
            }
            return;
        }
        log.info("building FTS5 index (one-time, skipping binary chunks)...");
        long t0 = System.currentTimeMillis();
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("CREATE VIRTUAL TABLE chunks_fts USING fts5(" +
                    "id UNINDEXED, text, tokenize='porter unicode61')");
        }
        int inserted = 0;
        int skipped = 0;
        boolean priorAuto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement read = connection.prepareStatement("SELECT id, text FROM chunks");
             PreparedStatement write = connection.prepareStatement(
                     "INSERT INTO chunks_fts(id, text) VALUES (?, ?)")) {
            try (ResultSet rs = read.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String text = rs.getString("text");
                    if (looksBinary(text)) {
                        skipped++;
                        continue;
                    }
                    write.setString(1, id);
                    write.setString(2, text);
                    write.executeUpdate();
                    inserted++;
                    if (inserted % 5000 == 0) {
                        connection.commit();
                    }
                }
            }
            connection.commit();
        } finally {
            connection.setAutoCommit(priorAuto);
        }
        log.info("FTS5 index built: {} indexed, {} skipped (binary), {} ms",
                inserted, skipped, System.currentTimeMillis() - t0);
    }

    private void loadEmbeddings() throws SQLException, IOException {
        log.info("loading embeddings...");
        List<String> ids = new ArrayList<>();
        List<float[]> vectors = new ArrayList<>();

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT chunk_id, vector FROM embeddings ORDER BY rowid")) {
            while (rs.next()) {
                ids.add(rs.getString("chunk_id"));
                vectors.add(vectorFromBlob(rs.getBytes("vector")));
            }
        }

        int n = vectors.size();
        float[] embeddingFlat = new float[n * DIM];
        String[] idArray = new String[n];
        for (int i = 0; i < n; i++) {
            idArray[i] = ids.get(i);
            float[] v = vectors.get(i);
            l2Normalize(v);
            System.arraycopy(v, 0, embeddingFlat, i * DIM, DIM);
        }
        this.chunkIds = idArray;
        this.allEmbeddings = embeddingFlat;
        log.info("{} vectors loaded and L2-normalized ({} MB RAM)",
                String.format("%,d", n), (n * DIM * 4) / 1_000_000);
    }

    private static void l2Normalize(float[] v) {
        double sum = 0.0;
        for (float x : v) sum += x * x;
        double mag = Math.sqrt(sum);
        if (mag <= 0) return;
        float inv = (float) (1.0 / mag);
        for (int i = 0; i < v.length; i++) v[i] *= inv;
    }

    private void loadChunkMeta() throws SQLException {
        Map<String, ChunkMeta> meta = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT id, spec_id, release, series, series_desc, doc_type, title FROM chunks")) {
            while (rs.next()) {
                String id = rs.getString("id");
                meta.put(id, new ChunkMeta(
                        id,
                        rs.getString("spec_id"),
                        rs.getString("release"),
                        rs.getString("series"),
                        rs.getString("series_desc"),
                        rs.getString("doc_type"),
                        rs.getString("title")
                ));
            }
        }
        this.chunkMeta = meta;
    }

    public List<SearchHit> search(float[] queryVector, int topK, String series, String release, String docType) throws SQLException {
        // Over-fetch so that we can filter out chunks whose text is binary/EMF/docx-XML garbage
        // and still return the requested topK clean results. Multiplier is a configurable
        // upper bound on how aggressively we widen the candidate pool.
        int oversample = Math.min(Math.max(topK * BINARY_FILTER_OVERSAMPLE, topK), MAX_OVERSAMPLE);
        List<ScoredId> scored = cosineTopK(queryVector, oversample, series, release, docType);

        List<SearchHit> out = new ArrayList<>(topK);
        try (PreparedStatement ps = connection.prepareStatement("SELECT text FROM chunks WHERE id=?")) {
            for (ScoredId id : scored) {
                if (out.size() >= topK) break;
                ChunkMeta meta = chunkMeta.get(id.chunkId());
                if (meta == null) {
                    continue;
                }
                ps.setString(1, id.chunkId());
                try (ResultSet rs = ps.executeQuery()) {
                    String text = rs.next() ? rs.getString("text") : "";
                    if (looksBinary(text)) {
                        continue;
                    }
                    String snippet = text == null ? "" : text;
                    double rounded = Math.round(id.score() * 10000.0d) / 10000.0d;
                    out.add(new SearchHit(rounded, meta.specId(), meta.release(), meta.title(), meta.seriesDesc(), snippet));
                }
            }
        }
        return out;
    }

    public List<SearchHit> hybridSearch(
            String rawQuery,
            float[] queryVector,
            int topK,
            String series,
            String release,
            String docType
    ) throws SQLException {
        int candidatePool = Math.min(Math.max(topK * HYBRID_CANDIDATE_MULT, 50), MAX_HYBRID_CANDIDATES);
        List<ScoredId> dense = cosineTopK(queryVector, candidatePool, series, release, docType);
        List<ScoredId> bm25 = bm25TopK(rawQuery, candidatePool, series, release, docType);

        // Reciprocal Rank Fusion. k=60 is the standard constant from Cormack et al.;
        // it is robust across corpora and avoids per-corpus tuning.
        Map<String, Double> rrf = new LinkedHashMap<>();
        for (int r = 0; r < dense.size(); r++) {
            rrf.merge(dense.get(r).chunkId(), 1.0 / (RRF_K + r + 1), Double::sum);
        }
        for (int r = 0; r < bm25.size(); r++) {
            rrf.merge(bm25.get(r).chunkId(), 1.0 / (RRF_K + r + 1), Double::sum);
        }
        // Normalize so that "ranked #1 in both rankers" = 1.0
        double maxRrf = 2.0 / (RRF_K + 1);

        List<Map.Entry<String, Double>> sorted = new ArrayList<>(rrf.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue().reversed());

        List<SearchHit> out = new ArrayList<>(topK);
        try (PreparedStatement ps = connection.prepareStatement("SELECT text FROM chunks WHERE id=?")) {
            for (Map.Entry<String, Double> e : sorted) {
                if (out.size() >= topK) break;
                ChunkMeta meta = chunkMeta.get(e.getKey());
                if (meta == null) continue;
                ps.setString(1, e.getKey());
                try (ResultSet rs = ps.executeQuery()) {
                    String text = rs.next() ? rs.getString("text") : "";
                    if (looksBinary(text)) continue;
                    String snippet = text == null ? "" : text;
                    double normalized = e.getValue() / maxRrf;
                    double rounded = Math.round(normalized * 10000.0d) / 10000.0d;
                    out.add(new SearchHit(rounded, meta.specId(), meta.release(),
                            meta.title(), meta.seriesDesc(), snippet));
                }
            }
        }
        return out;
    }

    private List<ScoredId> bm25TopK(String query, int k, String series, String release, String docType) throws SQLException {
        String fts = sanitizeFtsQuery(query);
        if (fts.isBlank()) return List.of();

        StringBuilder sql = new StringBuilder(
                "SELECT f.id AS id, bm25(chunks_fts) AS score " +
                        "FROM chunks_fts f JOIN chunks c ON c.id = f.id " +
                        "WHERE chunks_fts MATCH ?");
        List<Object> params = new ArrayList<>();
        params.add(fts);
        if (series != null && !series.isBlank()) {
            sql.append(" AND c.series = ?"); params.add(series);
        }
        if (release != null && !release.isBlank()) {
            sql.append(" AND c.release = ?"); params.add(release);
        }
        if (docType != null && !docType.isBlank()) {
            sql.append(" AND c.doc_type = ?"); params.add(docType);
        }
        sql.append(" ORDER BY score LIMIT ?");
        params.add(k);

        List<ScoredId> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // SQLite bm25() returns negative scores (lower = better match).
                    // Flip to positive so callers can treat higher-is-better consistently.
                    out.add(new ScoredId(rs.getString("id"), -rs.getFloat("score")));
                }
            }
        } catch (SQLException e) {
            // A malformed FTS query (rare with our sanitizer) should not crash search;
            // log and return empty so the dense ranker still contributes.
            log.warn("BM25 query failed for '{}': {}", fts, e.getMessage());
            return List.of();
        }
        return out;
    }

    private static String sanitizeFtsQuery(String query) {
        if (query == null) return "";
        String[] tokens = query.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            String clean = t.replaceAll("[^A-Za-z0-9-]", "").toLowerCase();
            if (clean.length() < 2) continue;
            if (sb.length() > 0) sb.append(" OR ");
            // Quote to suppress FTS5 syntax characters inside the token.
            sb.append('"').append(clean).append('"');
        }
        return sb.toString();
    }

    private static final int BINARY_FILTER_OVERSAMPLE = 4;
    private static final int MAX_OVERSAMPLE = 200;
    private static final int HYBRID_CANDIDATE_MULT = 10;
    private static final int MAX_HYBRID_CANDIDATES = 200;
    private static final int RRF_K = 60;

    static boolean looksBinary(String text) {
        if (text == null || text.isBlank()) return true;
        String s = text.length() > 600 ? text.substring(0, 600) : text;
        if (s.contains("EMF+") || s.contains("w:docVar") || s.contains("w:val=")) return true;
        int bad = 0;
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            if ((c < 32 && c != '\n' && c != '\r' && c != '\t') || c > 126) bad++;
        }
        return bad * 5 > s.length();
    }

    public List<Map<String, Object>> getSpecChunks(String specId, int maxChunks) throws SQLException {
        String sql = "SELECT * FROM chunks WHERE spec_id=? ORDER BY chunk_index LIMIT ?";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, specId);
            ps.setInt(2, maxChunks);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rowMap(rs));
                }
            }
        }
        return rows;
    }

    public List<Map<String, Object>> listSpecs(String series, String release) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT spec_id, series, series_desc, release, doc_type, MAX(total_chunks) AS total_chunks FROM chunks");
        List<Object> params = new ArrayList<>();
        List<String> where = new ArrayList<>();
        if (series != null && !series.isBlank()) {
            where.add("series=?");
            params.add(series);
        }
        if (release != null && !release.isBlank()) {
            where.add("release=?");
            params.add(release);
        }
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", where));
        }
        sql.append(" GROUP BY spec_id ORDER BY spec_id");

        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rowMap(rs));
                }
            }
        }
        return out;
    }

    public Set<String> indexedSeries() throws SQLException {
        Set<String> series = new HashSet<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT series FROM chunks")) {
            while (rs.next()) {
                series.add(rs.getString("series"));
            }
        }
        return series;
    }

    public long totalChunks() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) AS n FROM chunks")) {
            return rs.next() ? rs.getLong("n") : 0L;
        }
    }

    public long totalSpecs() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(DISTINCT spec_id) AS n FROM chunks")) {
            return rs.next() ? rs.getLong("n") : 0L;
        }
    }

    public String embedModelName() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT value FROM meta WHERE key='embed_model'")) {
            return rs.next() ? rs.getString("value") : "all-MiniLM-L6-v2";
        }
    }

    public int vectorCount() {
        String[] ids = chunkIds;
        return ids == null ? 0 : ids.length;
    }

    private List<ScoredId> cosineTopK(float[] qvec, int k, String series, String release, String docType) {
        String[] ids = chunkIds;
        float[] embeddings = allEmbeddings;
        Map<String, ChunkMeta> metaMap = chunkMeta;
        if (ids == null || embeddings == null || metaMap == null) {
            return List.of();
        }

        List<ScoredId> results = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i];
            ChunkMeta meta = metaMap.get(id);
            if (meta == null) {
                continue;
            }
            if (series != null && !series.isBlank() && !series.equals(meta.series())) {
                continue;
            }
            if (release != null && !release.isBlank() && !release.equals(meta.release())) {
                continue;
            }
            if (docType != null && !docType.isBlank() && !docType.equals(meta.docType())) {
                continue;
            }
            int off = i * DIM;
            float dot = 0f;
            for (int j = 0; j < DIM; j++) {
                dot += qvec[j] * embeddings[off + j];
            }
            results.add(new ScoredId(id, dot));
        }
        results.sort((a, b) -> Float.compare(b.score(), a.score()));
        return results.size() > k ? results.subList(0, k) : results;
    }

    private float[] vectorFromBlob(byte[] blob) throws IOException {
        if (blob == null || blob.length < DIM * 4) {
            throw new IOException("Invalid embedding vector blob");
        }
        float[] out = new float[DIM];
        ByteBuffer bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < DIM; i++) {
            out[i] = bb.getFloat();
        }
        return out;
    }

    private Map<String, Object> rowMap(ResultSet rs) throws SQLException {
        int colCount = rs.getMetaData().getColumnCount();
        Map<String, Object> map = new HashMap<>();
        for (int i = 1; i <= colCount; i++) {
            map.put(rs.getMetaData().getColumnName(i), rs.getObject(i));
        }
        return map;
    }

    private record ScoredId(String chunkId, float score) {
    }
}

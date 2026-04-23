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
    private volatile long[] chunkIds;
    private volatile Map<Long, ChunkMeta> chunkMeta;
    private volatile Connection connection;

    public void init(Path dbPath, StartupState startupState) throws SQLException, IOException {
        startupState.phase("loading-db");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        loadEmbeddings();
        loadChunkMeta();
    }

    private void loadEmbeddings() throws SQLException, IOException {
        log.info("loading embeddings...");
        List<Long> ids = new ArrayList<>();
        List<float[]> vectors = new ArrayList<>();

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT chunk_id, vector FROM embeddings ORDER BY rowid")) {
            while (rs.next()) {
                ids.add(rs.getLong("chunk_id"));
                vectors.add(vectorFromBlob(rs.getBytes("vector")));
            }
        }

        int n = vectors.size();
        float[] embeddingFlat = new float[n * DIM];
        long[] idArray = new long[n];
        for (int i = 0; i < n; i++) {
            idArray[i] = ids.get(i);
            System.arraycopy(vectors.get(i), 0, embeddingFlat, i * DIM, DIM);
        }
        this.chunkIds = idArray;
        this.allEmbeddings = embeddingFlat;
        log.info("{} vectors loaded ({} MB RAM)", String.format("%,d", n), (n * DIM * 4) / 1_000_000);
    }

    private void loadChunkMeta() throws SQLException {
        Map<Long, ChunkMeta> meta = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT id, spec_id, release, series, series_desc, doc_type, title FROM chunks")) {
            while (rs.next()) {
                long id = rs.getLong("id");
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
        List<ScoredId> scored = cosineTopK(queryVector, topK, series, release, docType);
        List<SearchHit> out = new ArrayList<>(scored.size());
        try (PreparedStatement ps = connection.prepareStatement("SELECT text FROM chunks WHERE id=?")) {
            for (ScoredId id : scored) {
                ChunkMeta meta = chunkMeta.get(id.chunkId());
                if (meta == null) {
                    continue;
                }
                ps.setLong(1, id.chunkId());
                try (ResultSet rs = ps.executeQuery()) {
                    String text = rs.next() ? rs.getString("text") : "";
                    String snippet = text.length() > 400 ? text.substring(0, 400) + "..." : text;
                    double rounded = Math.round(id.score() * 10000.0d) / 10000.0d;
                    out.add(new SearchHit(rounded, meta.specId(), meta.release(), meta.title(), meta.seriesDesc(), snippet));
                }
            }
        }
        return out;
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
        long[] ids = chunkIds;
        return ids == null ? 0 : ids.length;
    }

    private List<ScoredId> cosineTopK(float[] qvec, int k, String series, String release, String docType) {
        long[] ids = chunkIds;
        float[] embeddings = allEmbeddings;
        Map<Long, ChunkMeta> metaMap = chunkMeta;
        if (ids == null || embeddings == null || metaMap == null) {
            return List.of();
        }

        List<ScoredId> results = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            long id = ids[i];
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

    private record ScoredId(long chunkId, float score) {
    }
}

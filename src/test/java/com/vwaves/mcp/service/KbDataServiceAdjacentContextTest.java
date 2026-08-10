package com.vwaves.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vwaves.mcp.model.SearchHit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link KbDataService#adjacentContext} against a throwaway SQLite DB
 * seeded to reproduce the exact failure mode it targets: a procedure's prose
 * split across chunk boundaries, where the sentence completing a hit's thought
 * sits one chunk_index away.
 *
 * <p>rerankService/lexicon/props are null: adjacentContext() and the init()
 * path it depends on (loadEmbeddings/loadChunkMeta/buildFts5IfMissing) never
 * dereference them, only EmbeddingService.dim() is needed.
 */
class KbDataServiceAdjacentContextTest {

    private Path dbFile;
    private KbDataService kb;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("adjacent-context-test", ".db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile)) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE TABLE chunks (" +
                        "id TEXT PRIMARY KEY, spec_id TEXT, release TEXT, series TEXT, " +
                        "series_desc TEXT, doc_type TEXT, title TEXT, chunk_index TEXT, text TEXT)");
                st.executeUpdate("CREATE TABLE embeddings (chunk_id TEXT, vector BLOB)");

                insertChunk(st, "c0", "38.331", "0",
                        "The UE shall initiate the measurement reporting procedure.");
                insertChunk(st, "c1", "38.331", "1",
                        "Upon expiry of timer T304, the UE shall consider the handover as failed.");
                insertChunk(st, "c2", "38.331", "2",
                        "The UE shall then revert to the source cell configuration.");
                // Different spec entirely, same chunk_index values — must never leak in
                // as a neighbor of 38.331's chunks.
                insertChunk(st, "x1", "23.501", "1", "Unrelated architecture text.");
                // No chunk_index at all — the -1 short-circuit path.
                insertChunk(st, "u0", "99.999", "", "Chunk with unknown position.");
            }
        }

        kb = new KbDataService(null, null, null, new EmbeddingService(null, "test", 8));
        kb.init(List.of(dbFile), new StartupState());
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    private static void insertChunk(Statement st, String id, String specId,
                                     String chunkIndex, String text) throws SQLException {
        st.executeUpdate("INSERT INTO chunks "
                + "(id, spec_id, release, series, series_desc, doc_type, title, chunk_index, text) "
                + "VALUES ('" + id + "', '" + specId + "', 'Rel-18', '38', 'NR', 'TS', 'title', "
                + (chunkIndex.isEmpty() ? "NULL" : "'" + chunkIndex + "'") + ", '" + text + "')");
    }

    private SearchHit hitFor(String chunkId, String specId, int chunkIndex) {
        // 0-prefixed: KbDataService prefixes chunk ids with the DB index it loaded from.
        return new SearchHit(0.9, specId, "Rel-18", "title", "NR", "snippet",
                "0:" + chunkId, "TS", 2, chunkIndex);
    }

    @Test
    void middleChunkSeesBothNeighbors() throws SQLException {
        KbDataService.AdjacentContext ctx = kb.adjacentContext(hitFor("c1", "38.331", 1), 200);
        assertEquals("The UE shall initiate the measurement reporting procedure.", ctx.before());
        assertEquals("The UE shall then revert to the source cell configuration.", ctx.after());
        assertTrue(!ctx.isEmpty());
    }

    @Test
    void firstChunkHasNoBeforeNeighbor() throws SQLException {
        KbDataService.AdjacentContext ctx = kb.adjacentContext(hitFor("c0", "38.331", 0), 200);
        assertNull(ctx.before());
        assertEquals("Upon expiry of timer T304, the UE shall consider the handover as failed.",
                ctx.after());
    }

    @Test
    void lastChunkHasNoAfterNeighbor() throws SQLException {
        KbDataService.AdjacentContext ctx = kb.adjacentContext(hitFor("c2", "38.331", 2), 200);
        assertEquals("Upon expiry of timer T304, the UE shall consider the handover as failed.",
                ctx.before());
        assertNull(ctx.after());
    }

    @Test
    void neverCrossesIntoADifferentSpecAtTheSameChunkIndex() throws SQLException {
        // 23.501 has a chunk_index=1 too, but it must not be picked up as 38.331's
        // neighbor at index 0 or index 2 — the lookup is scoped by specId first.
        KbDataService.AdjacentContext ctx = kb.adjacentContext(hitFor("c0", "38.331", 0), 200);
        assertTrue(ctx.after() == null
                || !ctx.after().contains("Unrelated architecture"));
    }

    @Test
    void unknownChunkIndexShortCircuitsToEmpty() throws SQLException {
        KbDataService.AdjacentContext ctx = kb.adjacentContext(hitFor("u0", "99.999", -1), 200);
        assertTrue(ctx.isEmpty());
    }

    @Test
    void previewIsTruncatedToRequestedLength() throws SQLException {
        KbDataService.AdjacentContext ctx = kb.adjacentContext(hitFor("c1", "38.331", 1), 10);
        assertEquals(10, ctx.before().length());
        assertEquals(10, ctx.after().length());
    }
}

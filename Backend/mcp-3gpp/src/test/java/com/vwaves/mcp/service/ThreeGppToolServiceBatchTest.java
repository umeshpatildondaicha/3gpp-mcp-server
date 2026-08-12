package com.vwaves.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vwaves.mcp.model.SearchFilter;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * search3gppBatch — the JSON reply. Every assertion goes through a Jackson
 * parse of the returned string, never through substring positions, because the
 * per-query work runs on a concurrent pool and only the JSON structure (keyed
 * by the caller's own query strings) is part of the contract.
 */
class ThreeGppToolServiceBatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ThreeGppToolServiceTestHarness h = new ThreeGppToolServiceTestHarness();

    private JsonNode batch(List<String> queries, Integer topK, String series, String release,
                           String docType, String verbosity, Integer maxPerSpec) throws Exception {
        return MAPPER.readTree(h.service.search3gppBatch(
                queries, topK, series, release, docType, verbosity, maxPerSpec));
    }

    // ── Empty / null input ───────────────────────────────────────────────────

    @Test
    @DisplayName("null, empty and all-blank query lists come back as a JSON error")
    void emptyInputsAreJsonErrors() throws Exception {
        for (List<String> bad : new ArrayList<List<String>>() {{
            add(null);
            add(List.of());
            add(List.of("   ", "\t"));
        }}) {
            JsonNode root = batch(bad, null, null, null, null, null, null);
            assertThat(root.has("error")).as("input: %s", bad).isTrue();
            assertThat(root.get("error").asText())
                    .contains("`queries` is required")
                    .contains("single-query search tool");
        }
    }

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("two queries: JSON keyed by query, hits carry the FULL field set")
    void happyPathKeyedByQuery() throws Exception {
        when(h.kb.hybridSearchDetailed(anyString(), anyString(), any(float[].class), anyInt(),
                any(SearchFilter.class), nullable(Integer.class)))
                .thenAnswer(inv -> {
                    String q = inv.getArgument(0);
                    return q.equals("alpha topic")
                            ? ThreeGppToolServiceTestHarness.resultOf(
                                    ThreeGppToolServiceTestHarness.hit(0.91, "38.331",
                                            "NR RRC", "Radio", "alpha snippet text"))
                            : ThreeGppToolServiceTestHarness.resultOf(
                                    ThreeGppToolServiceTestHarness.hit(0.72, "23.501",
                                            "5G architecture", "SA", "beta snippet text"));
                });

        JsonNode root = batch(List.of("alpha topic", "beta topic"), null, null, null, null, null, null);

        assertThat(root.get("_meta").get("queries").asInt()).isEqualTo(2);
        assertThat(root.get("_meta").has("dropped")).isFalse();
        assertThat(root.get("_meta").has("filters_applied_to_every_query")).isFalse();

        JsonNode alpha = root.get("alpha topic");
        assertThat(alpha.get("intent").asText()).isEqualTo("HINT-LOOKUP");
        assertThat(alpha.get("top_score").asDouble()).isEqualTo(0.95); // mocked confidenceOf
        assertThat(alpha.get("margin").asDouble()).isEqualTo(0.15);
        assertThat(alpha.get("distinct_specs").asInt()).isEqualTo(3);
        assertThat(alpha.get("retrievers_agree").asText()).isEqualTo("2/2");
        JsonNode hitA = alpha.get("hits").get(0);
        assertThat(hitA.get("n").asInt()).isEqualTo(1);
        assertThat(hitA.get("spec_id").asText()).isEqualTo("38.331");
        assertThat(hitA.get("score").asDouble()).isEqualTo(0.91);
        assertThat(hitA.get("title").asText()).isEqualTo("NR RRC");
        assertThat(hitA.get("series").asText()).isEqualTo("Radio");
        assertThat(hitA.get("release").asText()).isEqualTo("Rel-18");
        assertThat(hitA.get("excerpt").asText()).isEqualTo("alpha snippet text");

        JsonNode hitB = root.get("beta topic").get("hits").get(0);
        assertThat(hitB.get("spec_id").asText()).isEqualTo("23.501");

        // Batch default topK is 5, not the single-query 10.
        verify(h.kb, times(2)).hybridSearchDetailed(anyString(), anyString(), any(float[].class),
                eq(5), any(SearchFilter.class), nullable(Integer.class));
    }

    @Test
    @DisplayName("batch-wide filters are flagged in _meta with the re-run advice")
    void filtersAreReported() throws Exception {
        JsonNode root = batch(List.of("filtered topic"), 3, "38", null, "TS", null, null);

        assertThat(root.get("_meta").get("filters_applied_to_every_query").asBoolean()).isTrue();
        assertThat(root.get("_meta").get("filter_note").asText())
                .contains("applied to ALL queries")
                .contains("Re-run THAT ONE query alone");
        verify(h.kb).hybridSearchDetailed(eq("filtered topic"), anyString(), any(float[].class),
                eq(3), eq(new SearchFilter("38", null, "TS")), nullable(Integer.class));
    }

    // ── Overflow ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("queries beyond the cap of 20 are dropped, marked not_searched and explained")
    void overflowQueriesAreMarkedNotSearched() throws Exception {
        List<String> queries = new ArrayList<>();
        for (int i = 1; i <= 23; i++) queries.add("query number " + i);

        JsonNode root = batch(queries, 1, null, null, null, null, null);

        JsonNode meta = root.get("_meta");
        assertThat(meta.get("queries").asInt()).isEqualTo(20);
        assertThat(meta.get("dropped").asInt()).isEqualTo(3);
        assertThat(meta.get("dropped_note").asText())
                .contains("the limit is 20 queries per call")
                .contains("cannot report them as not found");
        for (int i = 21; i <= 23; i++) {
            JsonNode over = root.get("query number " + i);
            assertThat(over.get("not_searched").asBoolean()).isTrue();
            assertThat(over.has("hits")).isFalse();
        }
        // The searched ones all carry hits.
        assertThat(root.get("query number 1").get("hits")).isNotNull();
        verify(h.kb, times(20)).hybridSearchDetailed(anyString(), anyString(), any(float[].class),
                anyInt(), any(SearchFilter.class), nullable(Integer.class));
    }

    // ── Partial failure ──────────────────────────────────────────────────────

    @Test
    @DisplayName("one query throwing loses only its own entry; the others still answer")
    void oneFailingQueryDoesNotSinkTheBatch() throws Exception {
        when(h.kb.hybridSearchDetailed(anyString(), anyString(), any(float[].class), anyInt(),
                any(SearchFilter.class), nullable(Integer.class)))
                .thenAnswer(inv -> {
                    String q = inv.getArgument(0);
                    if (q.equals("boom")) throw new SQLException("kb exploded");
                    return ThreeGppToolServiceTestHarness.resultOf(
                            ThreeGppToolServiceTestHarness.hit(0.8, "38.331", "T", "S", "ok"));
                });

        JsonNode root = batch(List.of("works fine", "boom"), null, null, null, null, null, null);

        assertThat(root.get("works fine").get("hits").get(0).get("spec_id").asText())
                .isEqualTo("38.331");
        assertThat(root.get("boom").get("error").asText())
                .isEqualTo("SQLException: kb exploded");
        assertThat(root.get("boom").has("hits")).isFalse();
    }

    @Test
    @DisplayName("an out-of-scope query becomes a JSON error entry, not a text block")
    void outOfScopeInsideBatch() throws Exception {
        when(h.scopeGate.outOfScopeReason("oos topic")).thenReturn("Not covered by this index.");

        JsonNode root = batch(List.of("oos topic", "normal topic"), null, null, null, null, null, null);

        assertThat(root.get("oos topic").get("error").asText())
                .isEqualTo("Not covered by this index.");
        assertThat(root.get("normal topic").get("hits")).isNotNull();
    }

    // ── No-match accounting ──────────────────────────────────────────────────

    @Test
    @DisplayName("empty-hit queries are marked no_match and counted once in _meta")
    void noMatchQueriesAreCounted() throws Exception {
        when(h.kb.hybridSearchDetailed(anyString(), anyString(), any(float[].class), anyInt(),
                any(SearchFilter.class), nullable(Integer.class)))
                .thenAnswer(inv -> {
                    String q = inv.getArgument(0);
                    return q.startsWith("miss")
                            ? new KbDataService.HybridResult(List.of(), Map.of())
                            : ThreeGppToolServiceTestHarness.resultOf(
                                    ThreeGppToolServiceTestHarness.hit(0.8, "38.331", "T", "S", "ok"));
                });

        JsonNode root = batch(List.of("hit topic", "miss one", "miss two"),
                null, null, null, null, null, null);

        assertThat(root.get("miss one").get("no_match").asBoolean()).isTrue();
        assertThat(root.get("miss one").get("hits")).isEmpty();
        assertThat(root.get("miss two").get("no_match").asBoolean()).isTrue();
        assertThat(root.get("_meta").get("no_match").asInt()).isEqualTo(2);
        assertThat(root.get("_meta").get("no_match_note").asText())
                .contains("Nothing in the index matched");
        assertThat(root.get("hit topic").has("no_match")).isFalse();
    }

    @Test
    @DisplayName("queries are trimmed and blank entries skipped before searching")
    void queriesAreTrimmedAndBlanksDropped() throws Exception {
        JsonNode root = batch(java.util.Arrays.asList("  padded topic  ", null, "   "),
                null, null, null, null, null, null);

        assertThat(root.get("_meta").get("queries").asInt()).isEqualTo(1);
        assertThat(root.has("padded topic")).isTrue();
        verify(h.kb).hybridSearchDetailed(eq("padded topic"), anyString(), any(float[].class),
                anyInt(), any(SearchFilter.class), nullable(Integer.class));
    }

    @Test
    @DisplayName("an input-guard failure inside a batch item renders as that item's JSON error")
    void inputGuardErrorInsideBatch() throws Exception {
        JsonNode root = batch(List.of("x".repeat(700)), null, null, null, null, null, null);

        // The 700-char key is the query itself, trimmed.
        JsonNode entry = root.get("x".repeat(700));
        assertThat(entry.get("error").asText()).contains("Query rejected");
    }
}

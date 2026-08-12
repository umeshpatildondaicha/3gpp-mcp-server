package com.vwaves.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vwaves.mcp.model.SearchFilter;
import com.vwaves.mcp.model.SearchHit;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The non-search tools: getSpecInfo, listSpecs, listSeries, kbStats,
 * lookupIeDefinition, validateAnswer and getProcedureFlow — happy paths and the
 * edges (empty inputs, no rows, clamping), all against mocked KB returns.
 */
class ThreeGppToolServiceInfoToolsTest {

    private final ThreeGppToolServiceTestHarness h = new ThreeGppToolServiceTestHarness();

    private static Map<String, Object> chunkRow(int chunkIndex, String text) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("doc_type", "TS");
        row.put("spec_id", "38.331");
        row.put("title", "NR RRC protocol specification");
        row.put("release", "Rel-18");
        row.put("series", "38");
        row.put("series_desc", "Radio access");
        row.put("total_chunks", 120);
        row.put("chunk_index", chunkIndex);
        row.put("text", text);
        return row;
    }

    // ── getSpecInfo ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getSpecInfo")
    class GetSpecInfo {

        @Test
        void happyPathWithQueryShowsRankedChunks() throws SQLException {
            when(h.kb.getSpecChunks("38.331", 5, "paging occasion"))
                    .thenReturn(List.of(chunkRow(0, "chunk zero text"), chunkRow(7, "chunk seven text")));

            String out = h.service.getSpecInfo("38.331", null, "paging occasion");

            assertThat(out)
                    .contains("=== 3GPP TS 38.331 ===")
                    .contains("Title   : NR RRC protocol specification")
                    .contains("Release : Rel-18")
                    .contains("Series  : 38 - Radio access")
                    .contains("Chunks  : showing 2 of 120 total  (ranked for: \"paging occasion\")")
                    .contains("--- Chunk 1 ---")
                    .contains("chunk zero text")
                    .contains("--- Chunk 8 ---")
                    .contains("chunk seven text");
        }

        @Test
        void withoutQueryWarnsAboutDocumentOrder() throws SQLException {
            when(h.kb.getSpecChunks(eq("38.331"), anyInt(), anyString()))
                    .thenReturn(List.of(chunkRow(0, "cover page")));

            String out = h.service.getSpecInfo("38.331", null, null);
            assertThat(out).contains("(document order — pass query= to get the relevant chunks instead)");
        }

        @Test
        void unknownSpec() throws SQLException {
            when(h.kb.getSpecChunks(anyString(), anyInt(), anyString())).thenReturn(List.of());
            assertThat(h.service.getSpecInfo("99.999", null, null))
                    .isEqualTo("Spec '99.999' not found.");
        }

        @Test
        void maxChunksIsClampedTo20() throws SQLException {
            when(h.kb.getSpecChunks(anyString(), anyInt(), anyString()))
                    .thenReturn(List.of(chunkRow(0, "t")));
            h.service.getSpecInfo("38.331", 500, "q");
            verify(h.kb).getSpecChunks("38.331", 20, "q");
        }
    }

    // ── listSpecs / listSeries / kbStats ─────────────────────────────────────

    @Nested
    @DisplayName("catalog tools")
    class CatalogTools {

        @Test
        void listSpecsRendersTheTable() throws SQLException {
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("spec_id", "38.331");
            spec.put("doc_type", "TS");
            spec.put("release", "Rel-18");
            spec.put("total_chunks", 120);
            spec.put("series_desc", "Radio access");
            when(h.kb.listSpecs("38", "Rel-18")).thenReturn(List.of(spec));

            String out = h.service.listSpecs("38", "Rel-18");
            assertThat(out)
                    .contains("Indexed specs (1 total, all sources)")
                    .contains("Spec ID")
                    .contains("38.331")
                    .contains("Radio access");
        }

        @Test
        void listSpecsEmpty() throws SQLException {
            when(h.kb.listSpecs(nullable(String.class), nullable(String.class)))
                    .thenReturn(List.of());
            assertThat(h.service.listSpecs(null, null)).isEqualTo("No specs found.");
        }

        @Test
        void listSeriesMarksIndexedSeries() throws SQLException {
            Set<Map.Entry<String, String>> entries = new LinkedHashSet<>();
            entries.add(Map.entry("38", "Radio technology beyond LTE"));
            entries.add(Map.entry("99", "Not ingested series"));
            when(h.seriesCatalog.entries()).thenReturn(entries);

            String out = h.service.listSeries();
            assertThat(out)
                    .contains("3GPP Series Catalog")
                    .containsPattern("38\\s+yes\\s+Radio technology beyond LTE")
                    .containsPattern("99\\s+no\\s+Not ingested series");
        }

        @Test
        void kbStatsReportsTotalsAndSortedSeries() throws SQLException {
            when(h.kb.totalChunks()).thenReturn(999L);
            when(h.kb.totalSpecs()).thenReturn(42L);
            when(h.kb.stubSpecCount()).thenReturn(7L);
            when(h.kb.embedModelName()).thenReturn("bge-m3");

            String out = h.service.kbStats();
            assertThat(out)
                    .contains("Total chunks      : 999")
                    .contains("Unique specs      : 42")
                    .contains("  Substantive     : 35")
                    .contains("  Stub-only (≤2)  : 7")
                    .contains("Series            : 4 (23, 24, 36, 38)")
                    .contains("Embed model       : bge-m3");
        }
    }

    // ── lookupIeDefinition ───────────────────────────────────────────────────

    @Nested
    @DisplayName("lookupIeDefinition")
    class LookupIe {

        @Test
        void blankNameIsRejected() throws SQLException {
            assertThat(h.service.lookupIeDefinition("  ", null, null))
                    .isEqualTo("ieName is required, e.g. \"maxRetxThreshold\".");
        }

        @Test
        void noDefinitionFoundMentionsTheSeries() throws SQLException {
            when(h.kb.lookupIeDefinition("bogusIe", "36", 8)).thenReturn(List.of());

            String out = h.service.lookupIeDefinition("bogusIe", "36", null);
            assertThat(out)
                    .contains("No ASN.1 definition found for \"bogusIe\" in series 36.")
                    .contains("Do not infer its permitted values");
        }

        @Test
        void definitionsAreQuotedVerbatimWithContext() throws SQLException {
            when(h.kb.lookupIeDefinition("si-Periodicity", null, 8)).thenReturn(List.of(
                    new KbDataService.IeDefinition("36.331", "si-Periodicity-r13",
                            "si-Periodicity-r13 ENUMERATED {rf64, rf128}", "Rel-13", "NB-IoT SIB"),
                    new KbDataService.IeDefinition("36.331", "si-Periodicity-r14",
                            "si-Periodicity-r14 ENUMERATED {rf256}", "Rel-14", "MBMS")));

            String out = h.service.lookupIeDefinition("si-Periodicity", null, null);
            assertThat(out)
                    .contains("ASN.1 definitions for \"si-Periodicity\" (2 found)")
                    .contains("36.331 | Rel-13 | si-Periodicity-r13")
                    .contains("si-Periodicity-r13 ENUMERATED {rf64, rf128}")
                    .contains("context: ...NB-IoT SIB")
                    .contains("36.331 | Rel-14 | si-Periodicity-r14")
                    .contains("choose by");
        }

        @Test
        void limitIsClampedTo20() throws SQLException {
            when(h.kb.lookupIeDefinition(anyString(), nullable(String.class), anyInt()))
                    .thenReturn(List.of());
            h.service.lookupIeDefinition("prach-ConfigIndex", null, 999);
            verify(h.kb).lookupIeDefinition("prach-ConfigIndex", null, 20);
        }
    }

    // ── validateAnswer ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("validateAnswer")
    class ValidateAnswer {

        @Test
        void requiresBothInputs() throws SQLException {
            assertThat(h.service.validateAnswer("", "draft"))
                    .isEqualTo("Both question and draftAnswer are required.");
            assertThat(h.service.validateAnswer("question", "  "))
                    .isEqualTo("Both question and draftAnswer are required.");
        }

        @Test
        void draftWithoutSpecIdsHasNothingToValidate() throws SQLException {
            assertThat(h.service.validateAnswer("q", "an answer citing no specs at all"))
                    .contains("No 3GPP spec IDs found in the draft answer");
        }

        @Test
        void classifiesSupportedUnsupportedAndOmitted() throws SQLException {
            when(h.kb.hybridSearch(anyString(), anyString(), any(float[].class), eq(15),
                    eq(SearchFilter.NONE)))
                    .thenReturn(List.of(
                            ThreeGppToolServiceTestHarness.hit(0.9, "38.331", "T", "S", "x"),
                            ThreeGppToolServiceTestHarness.hit(0.8, "24.501", "T", "S", "y")));

            String out = h.service.validateAnswer("how does registration work",
                    "Per TS 38.331 and TS 23.501, the UE registers.");

            assertThat(out)
                    .contains("Citation check for: \"how does registration work\"")
                    .contains("Supported   (1): 38.331")
                    .contains("Unsupported (1): 23.501")
                    .contains("verify them with getSpecInfo, or drop the claim")
                    .contains("Retrieved but not cited (1): 24.501")
                    .contains("Retrieval confidence for the question: high (margin=0.150)");
        }
    }

    // ── getProcedureFlow ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("getProcedureFlow")
    class GetProcedureFlow {

        private final ProcedureLayerService.Layer stage2 = new ProcedureLayerService.Layer(
                "23", 1, "5G", 3, List.of("23.5"), "Stage-2 architecture");
        private final ProcedureLayerService.Layer stage3 = new ProcedureLayerService.Layer(
                "24", 2, "5G", 3, List.of("24.5"), "Stage-3 NAS");
        private final ProcedureLayerService.Layer notIndexed = new ProcedureLayerService.Layer(
                "77", 3, "5G", 3, List.of(), "Phantom layer");

        @Test
        void blankProcedureIsRejected() throws SQLException {
            assertThat(h.service.getProcedureFlow("  ", null, null))
                    .startsWith("Procedure is empty.");
        }

        @Test
        void noConfiguredLayers() throws SQLException {
            when(h.procedureConfig.layersFor(anyString())).thenReturn(List.of());
            assertThat(h.service.getProcedureFlow("PDU session establishment", null, null))
                    .isEqualTo("No procedure layers are configured. Check retrieval/procedure-layers.tsv.");
        }

        @Test
        void groupsEvidenceByLayerAndReportsEmptyLayers() throws SQLException {
            when(h.procedureConfig.layersFor("5G"))
                    .thenReturn(List.of(stage2, stage3, notIndexed));
            when(h.kb.hybridSearch(anyString(), anyString(), any(float[].class), anyInt(),
                    any(SearchFilter.class)))
                    .thenAnswer(inv -> {
                        SearchFilter f = inv.getArgument(4);
                        if ("23".equals(f.series())) {
                            return List.of(
                                    ThreeGppToolServiceTestHarness.hit(0.9, "23.502", "Procedures",
                                            "SA", "The AMF invokes the SMF."),
                                    // Test spec — must be filtered out of the evidence.
                                    ThreeGppToolServiceTestHarness.hit(0.85, "23.999", "Test spec",
                                            "SA", "conformance text"));
                        }
                        // Below the global floor max(0.15, 0.9*0.30=0.27) -> abstain.
                        return List.of(ThreeGppToolServiceTestHarness.hit(0.2, "24.501", "NAS",
                                "CT", "weak evidence"));
                    });
            when(h.lexicon.isTestSpec("23.999")).thenReturn(true);
            when(h.rerank.selectRelevantSentences(anyString(), anyString(), any()))
                    .thenReturn("The AMF invokes the SMF.");

            String out = h.service.getProcedureFlow("PDU session establishment", null, null);

            assertThat(out)
                    .contains("Cross-spec procedure evidence for: \"PDU session establishment\"")
                    .contains("=== Stage-2 architecture (series 23) ===")
                    // 23.502 is in stage2's preferred family: 0.9 * 1.15 caps at 1.0,
                    // which also lifts the global floor to 1.0 * 0.30 = 0.30.
                    .contains("23.502 | Rel-18 | chunk 4 | score 1.0")
                    .contains("The AMF invokes the SMF.")
                    .doesNotContain("23.999")
                    .contains("No strong evidence in: Stage-3 NAS (series 24)")
                    .contains("State that these legs are unsupported")
                    // The un-indexed layer is skipped silently — no evidence, no
                    // "no strong evidence" entry either.
                    .doesNotContain("Phantom layer")
                    .contains("Layers with evidence: 1 of 3 (evidence floor 0.30).");
        }

        @Test
        void preferredSpecFamilyGetsTheBoost() throws SQLException {
            when(h.procedureConfig.layersFor("5G")).thenReturn(List.of(stage2));
            // Two hits: the preferred-family spec starts behind but the 1.15x
            // boost must put it in front.
            when(h.kb.hybridSearch(anyString(), anyString(), any(float[].class), anyInt(),
                    any(SearchFilter.class)))
                    .thenReturn(List.of(
                            ThreeGppToolServiceTestHarness.hit(0.80, "23.401", "EPS", "SA", "eps text"),
                            ThreeGppToolServiceTestHarness.hit(0.75, "23.502", "5GS", "SA", "fivegs text")));

            String out = h.service.getProcedureFlow("registration", null, null);

            // Preferred spec 23.502 boosted to ~0.8625; sorted-by-spec output keeps
            // both, and the floor is computed off the boosted best.
            assertThat(out)
                    .contains("23.401")
                    .contains("23.502")
                    .contains("Layers with evidence: 1 of 1");
        }

        @Test
        void nothingAboveTheFloorMeansNoEvidence() throws SQLException {
            when(h.procedureConfig.layersFor("5G")).thenReturn(List.of(stage2));
            when(h.kb.hybridSearch(anyString(), anyString(), any(float[].class), anyInt(),
                    any(SearchFilter.class)))
                    .thenReturn(List.of(ThreeGppToolServiceTestHarness.hit(0.05, "23.502",
                            "Procedures", "SA", "noise")));

            String out = h.service.getProcedureFlow("teleportation setup", null, null);
            assertThat(out)
                    .startsWith("No cross-spec evidence found for procedure \"teleportation setup\".")
                    .contains("Try search3gpp");
        }

        @Test
        void perLayerOverrideIsClampedTo8() throws SQLException {
            when(h.procedureConfig.layersFor("LTE")).thenReturn(List.of(stage2));
            when(h.kb.hybridSearch(anyString(), anyString(), any(float[].class), anyInt(),
                    any(SearchFilter.class)))
                    .thenReturn(List.of(ThreeGppToolServiceTestHarness.hit(0.9, "23.401",
                            "EPS", "SA", "text")));

            h.service.getProcedureFlow("EPS attach", 99, "LTE");

            verify(h.kb).hybridSearch(anyString(), anyString(), any(float[].class), eq(8),
                    eq(SearchFilter.ofSeries("23")));
            verify(h.procedureConfig).layersFor("LTE");
        }

        @Test
        void hitsWithinALayerAreOrderedByDocumentPosition() throws SQLException {
            when(h.procedureConfig.layersFor("5G")).thenReturn(List.of(stage2));
            SearchHit late = new SearchHit(0.9, "23.502", "Rel-18", "Procedures", "SA",
                    "step nine text", "c9", "TS", 2, 9);
            SearchHit early = new SearchHit(0.8, "23.502", "Rel-18", "Procedures", "SA",
                    "step two text", "c2", "TS", 2, 2);
            when(h.kb.hybridSearch(anyString(), anyString(), any(float[].class), anyInt(),
                    any(SearchFilter.class)))
                    .thenReturn(List.of(late, early));

            String out = h.service.getProcedureFlow("PDU session establishment", null, null);

            // chunk 2 must render before chunk 9 despite the lower score.
            assertThat(out.indexOf("chunk 2")).isLessThan(out.indexOf("chunk 9"));
        }
    }
}

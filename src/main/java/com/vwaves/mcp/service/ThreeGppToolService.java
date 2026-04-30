package com.vwaves.mcp.service;

import com.vwaves.mcp.model.SearchHit;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
public class ThreeGppToolService {
    private static final Map<String, String> SERIES_MAP = Map.ofEntries(
            Map.entry("21", "Vocabulary, Requirements"),
            Map.entry("22", "Service Aspects & Stage 1"),
            Map.entry("23", "Architecture & Stage 2"),
            Map.entry("24", "Signalling & Stage 3 (UE-Network)"),
            Map.entry("25", "UTRAN / WCDMA Radio Access"),
            Map.entry("26", "Codecs & Media"),
            Map.entry("27", "Data"),
            Map.entry("28", "Telecom Management (OAM)"),
            Map.entry("29", "Core Network Protocols"),
            Map.entry("31", "SIM / USIM"),
            Map.entry("32", "OAM & Charging"),
            Map.entry("33", "Security"),
            Map.entry("34", "Test Specifications"),
            Map.entry("35", "Security Algorithms"),
            Map.entry("36", "LTE / E-UTRAN (4G)"),
            Map.entry("37", "Multi-RAT / Co-existence"),
            Map.entry("38", "NR / 5G Radio Access"),
            Map.entry("45", "GSM / EDGE Radio Access")
    );

    private final EmbeddingService embeddingService;
    private final KbDataService kbDataService;
    private final GlossaryService glossaryService;

    public ThreeGppToolService(
            EmbeddingService embeddingService,
            KbDataService kbDataService,
            GlossaryService glossaryService
    ) {
        this.embeddingService = embeddingService;
        this.kbDataService = kbDataService;
        this.glossaryService = glossaryService;
    }

    @Tool(description = "Semantic search across 3GPP specifications. Optional filters: series, release, docType.")
    public String search3gpp(
            @ToolParam(description = "Natural language search query") String query,
            @ToolParam(required = false, description = "Number of results to return (1-50, default 10)") Integer topK,
            @ToolParam(required = false, description = "Filter by 3GPP series, e.g. \"38\" for 5G NR") String series,
            @ToolParam(required = false, description = "Filter by release, e.g. \"Rel-18\"") String release,
            @ToolParam(required = false, description = "Filter by document type, e.g. \"TS\" or \"TR\"") String docType
    ) throws SQLException {
        String q = defaultValue(query, "");
        if (q.isBlank()) {
            return "Query is empty. Provide a natural-language search term, e.g. \"NR initial access\".";
        }
        if (series != null && !series.isBlank() && !kbDataService.indexedSeries().contains(series)) {
            return "Series '" + series + "' is not in the indexed knowledge base. "
                    + "Indexed series: " + String.join(", ", kbDataService.indexedSeries().stream().sorted().toList())
                    + ". Use the listSeries tool to see all 3GPP series and which are indexed.";
        }
        int k = topK == null ? 10 : Math.max(1, Math.min(50, topK));
        // Append acronym expansions so both BM25 and the embedder see the long form.
        String expanded = glossaryService.expand(q);
        List<SearchHit> hits = kbDataService.hybridSearch(
                expanded,
                embeddingService.embed(expanded),
                k, series, release, docType);
        return formatHits(hits, q);
    }

    @Tool(description = "Retrieve text chunks of a specific 3GPP spec by ID. Example specId: 38.331")
    public String getSpecInfo(
            @ToolParam(description = "3GPP spec ID, e.g. \"38.331\"") String specId,
            @ToolParam(required = false, description = "Max chunks to return (1-20, default 5)") Integer maxChunks
    ) throws SQLException {
        int chunks = maxChunks == null ? 5 : Math.max(1, Math.min(20, maxChunks));
        List<Map<String, Object>> rows = kbDataService.getSpecChunks(defaultValue(specId, ""), chunks);
        if (rows.isEmpty()) {
            return "Spec '" + specId + "' not found.";
        }
        Map<String, Object> first = rows.getFirst();
        List<String> lines = new ArrayList<>();
        lines.add("=== 3GPP " + first.get("doc_type") + " " + first.get("spec_id") + " ===");
        lines.add("Title   : " + first.get("title"));
        lines.add("Release : " + first.get("release"));
        lines.add("Series  : " + first.get("series") + " - " + first.get("series_desc"));
        lines.add("Chunks  : showing " + rows.size() + " of " + first.get("total_chunks") + " total");
        lines.add("");
        for (Map<String, Object> row : rows) {
            Number idx = (Number) row.get("chunk_index");
            lines.add("--- Chunk " + (idx.longValue() + 1) + " ---");
            lines.add(String.valueOf(row.get("text")));
            lines.add("");
        }
        return String.join("\n", lines);
    }

    @Tool(description = "List all 3GPP specs, optionally filtered by series or release.")
    public String listSpecs(
            @ToolParam(required = false, description = "Filter by 3GPP series, e.g. \"38\"") String series,
            @ToolParam(required = false, description = "Filter by release, e.g. \"Rel-18\"") String release
    ) throws SQLException {
        List<Map<String, Object>> specs = kbDataService.listSpecs(series, release);
        if (specs.isEmpty()) {
            return "No specs found.";
        }
        List<String> lines = new ArrayList<>();
        lines.add("Indexed 3GPP specs (" + specs.size() + " total)");
        lines.add("");
        lines.add(String.format("%-14s %-5s %-10s %-8s %s", "Spec ID", "Type", "Release", "Chunks", "Series"));
        lines.add("-".repeat(70));
        for (Map<String, Object> spec : specs) {
            lines.add(String.format("%-14s %-5s %-10s %-8s %s",
                    spec.get("spec_id"), spec.get("doc_type"), spec.get("release"), spec.get("total_chunks"), spec.get("series_desc")));
        }
        return String.join("\n", lines);
    }

    @Tool(description = "3GPP series catalog with index status.")
    public String listSeries() throws SQLException {
        Set<String> indexed = kbDataService.indexedSeries();
        List<String> lines = new ArrayList<>();
        lines.add("3GPP Series Catalog");
        lines.add("");
        lines.add(String.format("%-8s %-9s %s", "Series", "Indexed", "Description"));
        lines.add("-".repeat(55));
        SERIES_MAP.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> lines.add(String.format(
                        "%-8s %-9s %s",
                        entry.getKey(),
                        indexed.contains(entry.getKey()) ? "yes" : "no",
                        entry.getValue()
                )));
        return String.join("\n", lines);
    }

    @Tool(description = "Knowledge base statistics.")
    public String kbStats() throws SQLException {
        long total = kbDataService.totalChunks();
        long specs = kbDataService.totalSpecs();
        Set<String> series = kbDataService.indexedSeries();
        String model = kbDataService.embedModelName();

        List<String> lines = new ArrayList<>();
        lines.add("3GPP Knowledge Base Statistics");
        lines.add("=".repeat(40));
        lines.add("Total chunks  : " + NumberFormat.getIntegerInstance().format(total));
        lines.add("Unique specs  : " + specs);
        lines.add("Series        : " + series.size() + " (" + String.join(", ", series.stream().sorted().toList()) + ")");
        lines.add("Embed model   : " + model);
        lines.add("Transport     : Streamable HTTP");
        lines.add("Server version: 2.0.0");
        return String.join("\n", lines);
    }

    private String formatHits(List<SearchHit> hits, String query) {
        if (hits.isEmpty()) {
            return "No results found for: \"" + query + "\"";
        }
        List<String> lines = new ArrayList<>();
        lines.add("Search results for: \"" + query + "\"");
        lines.add("(" + hits.size() + " results)");
        lines.add("");
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            String snippet = h.snippet() == null || h.snippet().isBlank()
                    ? "(chunk has no extractable text — call getSpecInfo for full content)"
                    : h.snippet().replaceAll("\\s+", " ").strip();
            lines.add("[" + (i + 1) + "] " + h.specId() + " | " + h.release() + " | Score: " + h.score());
            lines.add("    Title  : " + h.title());
            lines.add("    Series : " + h.seriesDesc());
            lines.add("    Excerpt: " + snippet);
            lines.add("");
        }
        return String.join("\n", lines);
    }

    private String defaultValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}

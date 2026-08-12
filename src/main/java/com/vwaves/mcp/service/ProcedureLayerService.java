package com.vwaves.mcp.service;

import com.vwaves.mcp.config.RetrievalProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * Runtime configuration for {@code getProcedureFlow}: which specification layers
 * to retrieve from, and how to phrase the query.
 *
 * <p>Both tables were previously hard-coded — the layer list as a Java
 * {@code List.of(...)} and the query as the caller's raw wording. Neither could
 * be changed without a recompile, which is inconsistent with every other lexicon
 * in this service.
 */
@Service
public class ProcedureLayerService {
    private static final Logger log = LoggerFactory.getLogger(ProcedureLayerService.class);

    // ── procedure-layers TSV format (tab-separated columns) ──────────────────
    /** "alias" rows need at least: kind, alias, canonical. */
    private static final int ALIAS_MIN_FIELDS = 3;
    /** Column holding the canonical technology value in an "alias" row. */
    private static final int ALIAS_CANONICAL_COL = 2;
    /** "layer" rows need: kind, series, order, technology, perLayer, prefer, label. */
    private static final int LAYER_MIN_FIELDS = 7;
    /** Column holding the numeric display order in a "layer" row. */
    private static final int LAYER_ORDER_COL = 2;
    /** Column holding the technology ("5g", "4g", "any") in a "layer" row. */
    private static final int LAYER_TECHNOLOGY_COL = 3;
    /** Column holding the numeric per-layer hit budget in a "layer" row. */
    private static final int LAYER_PER_LAYER_COL = 4;
    /** Column holding the comma-separated preferred spec prefixes ("-" = none). */
    private static final int LAYER_PREFER_COL = 5;
    /** Column holding the human-readable layer label in a "layer" row. */
    private static final int LAYER_LABEL_COL = 6;

    /** procedure-synonyms rows are exactly: key TAB expansion. */
    private static final int SYNONYM_FIELD_COUNT = 2;

    /**
     * @param prefer spec-id prefixes boosted within this layer. A ranking nudge,
     *        not a filter, so interworking procedures still surface the other
     *        generation's specs when they genuinely matter.
     */
    public record Layer(String series, int order, String technology,
                        int perLayer, List<String> prefer, String label) {}

    private final List<Layer> layers;
    private final Map<String, String> techAliases;
    private final Map<String, String> synonyms;

    public ProcedureLayerService(ResourceLoader resourceLoader, RetrievalProperties props) {
        Map<String, String> aliases = new LinkedHashMap<>();
        this.layers = loadLayers(resourceLoader, props.procedureLayersPath(), aliases);
        // Collections.unmodifiableMap, NOT Map.copyOf: expansion and alias matching
        // iterate these maps, and Map.copyOf randomises iteration order per JVM —
        // the same query could expand differently across restarts. The wrapper
        // keeps the LinkedHashMap's file order, so behavior follows the table.
        this.techAliases = Collections.unmodifiableMap(aliases);
        this.synonyms = loadSynonyms(resourceLoader, props.procedureSynonymsPath());
        log.info("procedure config: {} layer(s), {} technology alias(es), {} synonym(s)",
                layers.size(), techAliases.size(), synonyms.size());
    }

    /**
     * Resolve a caller's technology wording to a canonical value using the alias
     * table. Unrecognised input falls back to "5g", the dominant case.
     */
    String canonicalTechnology(String technology) {
        if (technology == null || technology.isBlank()) return "5g";
        String t = technology.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : techAliases.entrySet()) {
            if (t.contains(e.getKey())) return e.getValue();
        }
        return "5g";
    }

    /**
     * Layers applicable to a technology request, in display order.
     * "both" (or anything unrecognised) returns every row, which is what an
     * interworking procedure such as SRVCC or EPS fallback needs.
     */
    public List<Layer> layersFor(String technology) {
        String canon = canonicalTechnology(technology);
        String want = "both".equals(canon) ? null : canon;
        List<Layer> out = new ArrayList<>();
        for (Layer l : layers) {
            String t = l.technology().toLowerCase(Locale.ROOT);
            if (want == null || t.equals("any") || t.equals(want)) out.add(l);
        }
        out.sort(Comparator.comparingInt(Layer::order).thenComparing(Layer::series));
        return out;
    }

    /**
     * Expand the procedure name toward the wording the specs actually use.
     * Terms are appended, never substituted, so a wrong match dilutes the query
     * slightly rather than redirecting it to a different procedure.
     */
    public String expandProcedure(String procedure) {
        if (procedure == null || procedure.isBlank() || synonyms.isEmpty()) return procedure;
        String p = procedure.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(procedure);
        for (Map.Entry<String, String> e : synonyms.entrySet()) {
            if (p.contains(e.getKey())) sb.append(' ').append(e.getValue());
        }
        return sb.toString();
    }

    /** True when the spec is in this layer's preferred family. */
    public static boolean isPreferred(Layer layer, String specId) {
        if (specId == null || layer.prefer().isEmpty()) return false;
        for (String prefix : layer.prefer()) {
            if (specId.startsWith(prefix)) return true;
        }
        return false;
    }

    // ── loading ──────────────────────────────────────────────────────────────

    private static List<Layer> loadLayers(ResourceLoader rl, String path,
                                          Map<String, String> aliasesOut) {
        List<Layer> out = new ArrayList<>();
        Resource resource = rl.getResource(path);
        if (!resource.exists()) {
            log.warn("procedure-layers not found at {} — getProcedureFlow disabled", path);
            return List.copyOf(out);
        }
        try (BufferedReader br = reader(resource)) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                parseLayersLine(line, lineNo, out, aliasesOut);
            }
        } catch (IOException e) {
            log.warn("failed reading procedure-layers from {}: {}", path, e.getMessage());
        }
        return List.copyOf(out);
    }

    private static void parseLayersLine(String line, int lineNo, List<Layer> out,
                                        Map<String, String> aliasesOut) {
        String t = line.strip();
        if (t.isEmpty() || t.startsWith("#")) return;
        String[] p = t.split("\t");
        String kind = p[0].strip().toLowerCase(Locale.ROOT);
        if ("alias".equals(kind) && p.length >= ALIAS_MIN_FIELDS) {
            aliasesOut.put(p[1].strip().toLowerCase(Locale.ROOT),
                    p[ALIAS_CANONICAL_COL].strip().toLowerCase(Locale.ROOT));
            return;
        }
        if (!"layer".equals(kind) || p.length < LAYER_MIN_FIELDS) {
            log.warn("procedure-layers line {}: unrecognised row '{}' — skipped", lineNo, t);
            return;
        }
        parseLayerRow(p, lineNo, out);
    }

    private static void parseLayerRow(String[] p, int lineNo, List<Layer> out) {
        try {
            List<String> prefer = "-".equals(p[LAYER_PREFER_COL].strip())
                    ? List.of()
                    : Arrays.stream(p[LAYER_PREFER_COL].split(",")).map(String::strip).toList();
            out.add(new Layer(p[1].strip(), Integer.parseInt(p[LAYER_ORDER_COL].strip()),
                    p[LAYER_TECHNOLOGY_COL].strip(), Integer.parseInt(p[LAYER_PER_LAYER_COL].strip()),
                    prefer, p[LAYER_LABEL_COL].strip()));
        } catch (NumberFormatException e) {
            log.warn("procedure-layers line {}: non-numeric order/perLayer — skipped", lineNo);
        }
    }

    private static Map<String, String> loadSynonyms(ResourceLoader rl, String path) {
        Map<String, String> out = new LinkedHashMap<>();
        Resource resource = rl.getResource(path);
        if (!resource.exists()) {
            log.warn("procedure-synonyms not found at {} — no query expansion", path);
            return Collections.unmodifiableMap(out);
        }
        try (BufferedReader br = reader(resource)) {
            String line;
            while ((line = br.readLine()) != null) {
                parseSynonymLine(line, out);
            }
        } catch (IOException e) {
            log.warn("failed reading procedure-synonyms from {}: {}", path, e.getMessage());
        }
        // File order preserved — see the techAliases comment in the constructor.
        return Collections.unmodifiableMap(out);
    }

    private static void parseSynonymLine(String line, Map<String, String> out) {
        String t = line.strip();
        if (t.isEmpty() || t.startsWith("#")) return;
        String[] p = t.split("\t", SYNONYM_FIELD_COUNT);
        if (p.length != SYNONYM_FIELD_COUNT) return;
        String k = p[0].strip().toLowerCase(Locale.ROOT);
        String v = p[1].strip();
        if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
    }

    private static BufferedReader reader(Resource r) throws IOException {
        InputStream in = r.getInputStream();
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /** Visible for tests. */
    int layerCount() { return layers.size(); }

    int synonymCount() { return synonyms.size(); }
}

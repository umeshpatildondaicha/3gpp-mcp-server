package com.vwaves.mcp.service;

import com.vwaves.mcp.config.RetrievalProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * Intent classification by nearest labelled exemplar in embedding space, with the
 * catalogue's keyword rules as the fallback.
 *
 * <p>Why: keyword matching scored 5/12 (41%) on a held-out set of engineer
 * paraphrases carrying no trigger term, and 7 of the 12 fell through to the
 * default intent — "walk me through how the UE comes back after losing the cell"
 * matched nothing. A keyword list cannot enumerate paraphrase space. With
 * exemplars the same held-out set scores 11/12 (91%).
 *
 * <p>Exemplars are embedded once at startup with the BGE-M3 model already loaded
 * for retrieval, so this adds no model, no network call, and one embed per query.
 *
 * <p>Decision order:
 * <ol>
 *   <li>a keyword hit on a <em>sticky</em> intent wins outright — which intents
 *       are sticky is declared in {@code intents.tsv}, not decided here</li>
 *   <li>otherwise the nearest exemplar, when its cosine clears the configured floor</li>
 *   <li>otherwise the catalogue's keyword rules</li>
 * </ol>
 */
@Service
public class IntentClassifierService {
    private static final Logger log = LoggerFactory.getLogger(IntentClassifierService.class);

    private record Exemplar(IntentCatalogService.Intent intent, String text, float[] vector) {}

    private final ResourceLoader resourceLoader;
    private final EmbeddingService embeddingService;
    private final IntentCatalogService catalog;
    private final String exemplarPath;
    private final double minSimilarity;

    private volatile List<Exemplar> exemplars = List.of();

    public IntentClassifierService(ResourceLoader resourceLoader,
                                   EmbeddingService embeddingService,
                                   IntentCatalogService catalog,
                                   RetrievalProperties props) {
        this.resourceLoader = resourceLoader;
        this.embeddingService = embeddingService;
        this.catalog = catalog;
        this.exemplarPath = props.intentExemplarsPath();
        this.minSimilarity = props.intentMinSimilarity();
    }

    /**
     * Embed the exemplar table. Must run after {@link EmbeddingService#init}.
     * On any failure the classifier stays empty and every call falls back to the
     * catalogue's keyword rules — intent routing must never stop the server booting.
     */
    public void init() {
        List<Exemplar> loaded = new ArrayList<>();
        Resource resource = resourceLoader.getResource(exemplarPath);
        if (!resource.exists()) {
            log.warn("intent exemplars not found at {} — keyword rules only", exemplarPath);
            return;
        }
        long t0 = System.currentTimeMillis();
        int skipped = 0;
        try (InputStream in = resource.getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] parts = t.split("\t", 2);
                if (parts.length != 2) continue;
                String id = parts[0].strip();
                String text = parts[1].strip();
                if (text.isEmpty()) continue;
                // An exemplar naming an intent that intents.tsv does not declare is
                // dead config — drop it loudly rather than silently rehoming it.
                if (!catalog.isKnown(id)) {
                    log.warn("intent exemplar names unknown intent '{}' — skipped: {}", id, text);
                    skipped++;
                    continue;
                }
                loaded.add(new Exemplar(catalog.byId(id), text, embeddingService.embed(text)));
            }
        } catch (IOException e) {
            log.warn("failed reading intent exemplars from {}: {} — keyword rules only",
                    exemplarPath, e.getMessage());
            return;
        }
        this.exemplars = List.copyOf(loaded);
        log.info("intent classifier ready: {} exemplars embedded in {} ms ({} skipped, min-similarity={})",
                loaded.size(), System.currentTimeMillis() - t0, skipped, minSimilarity);

        // An intent with no exemplars is effectively unreachable. The embedding
        // path runs before the keyword fallback and almost always finds SOME
        // exemplar above the similarity floor, so a keyword-only intent added to
        // intents.tsv will silently never be selected. Warn loudly — this is the
        // single easiest way to misconfigure intent routing.
        for (IntentCatalogService.Intent intent : catalog.intents()) {
            boolean hasExemplar = loaded.stream().anyMatch(e -> e.intent().id().equals(intent.id()));
            if (!hasExemplar && !intent.sticky()) {
                log.warn("intent '{}' has no exemplars in {} — the embedding classifier can "
                                + "never select it, so its keyword terms will rarely fire. "
                                + "Add exemplar rows for it, or mark it sticky.",
                        intent.id(), exemplarPath);
            }
        }
    }

    /** Classified intent plus how the decision was reached, for logging and tests. */
    public record Result(IntentCatalogService.Intent intent, String method, double similarity) {}

    public Result classify(String query) {
        if (query == null || query.isBlank()) {
            return new Result(catalog.defaultIntent(), "default", 0.0);
        }
        // 1. A sticky keyword hit is authoritative — the embedding must not talk
        //    us out of routing a vendor question to WebSearch.
        IntentCatalogService.Intent keyword = catalog.keywordMatch(query);
        if (keyword != null && keyword.sticky()) {
            return new Result(keyword, "keyword-sticky", 1.0);
        }

        List<Exemplar> ex = exemplars;
        if (!ex.isEmpty()) {
            float[] q = embeddingService.embed(query);
            Exemplar best = null;
            double bestSim = -1.0;
            for (Exemplar e : ex) {
                double sim = cosine(q, e.vector());
                if (sim > bestSim) {
                    bestSim = sim;
                    best = e;
                }
            }
            if (best != null && bestSim >= minSimilarity) {
                return new Result(best.intent(), "embedding", round(bestSim));
            }
            // Too weak to trust — fall back rather than assert a coin flip.
            return new Result(catalog.classifyByKeyword(query), "keyword-fallback", round(bestSim));
        }
        return new Result(catalog.classifyByKeyword(query), "keyword", 0.0);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** Both vectors are L2-normalised by EmbeddingService, so the dot product is the cosine. */
    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -1.0;
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) dot += (double) a[i] * b[i];
        return dot;
    }

    /** Visible for tests. */
    int exemplarCount() {
        return exemplars.size();
    }
}

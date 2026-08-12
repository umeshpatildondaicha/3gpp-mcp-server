package com.vwaves.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All retrieval-pipeline tunables. Bound from {@code app.retrieval.*} in
 * application.properties, env-var overridable via Spring's standard
 * {@code ${VAR:default}} syntax.
 *
 * Defaults reflect the values that produced the OAM benchmark TOP1 = 36/50
 * (72%) on 2026-05-08; do not change them without re-running benchmark_oam.py.
 */
@ConfigurationProperties(prefix = "app.retrieval")
public record RetrievalProperties(
        // ── Per-spec diversity caps ─────────────────────────────────────
        int maxDensePerSpec,
        int maxHybridPerSpec,
        int maxRerankPerSpec,

        // ── Candidate pool sizes ────────────────────────────────────────
        int rerankCandidates,
        int hybridCandidateMult,
        int maxHybridCandidates,
        int binaryFilterOversample,
        int maxOversample,

        // ── Scoring ─────────────────────────────────────────────────────
        double minResultScore,
        double coOccurrenceBoost,
        int rrfK,
        double studyReportDiscount,
        double extrasDbDiscount,
        double extrasDbNeutralWeight,

        // ── Release-aware ranking ───────────────────────────────────────
        // Applied post-rerank when the query names a release (e.g. "Rel-18")
        // and the hit carries that same release. Rewards release consistency
        // instead of leaving release purely as a hard filter.
        double releaseMatchBoost,
        double releaseMismatchDiscount,

        // ── Retriever agreement ─────────────────────────────────────────
        // How deep into each retriever's own ranking to look when deciding
        // whether dense and BM25 independently support a spec. Feeds the
        // confidence signal, not the score.
        int agreementTopN,

        // ── Confidence gate ─────────────────────────────────────────────
        // Calibrated on the 100-question benchmark (2026-07-27): the ABSOLUTE top
        // score does not separate correct from incorrect retrievals (median 0.803
        // vs 0.769), but the rank1−rank2 margin does. At margin >= 0.12 top-1 is
        // correct 84% of the time vs 51% below it. The gate is binary on purpose —
        // an intermediate band did not rank between the two (see confidenceOf()).
        double confidenceHighMargin,
        // "none" tier: the conjunction that means nothing matched at all.
        double confidenceNoneTopScore,
        double confidenceNoneMargin,

        // ── Stub suppression ────────────────────────────────────────────
        int stubMaxChunksPerSpec,
        int stubMaxChunkTextLength,

        // ── BM25 query construction ─────────────────────────────────────
        int andTermLimit,

        // ── Study-report numeric range (3GPP convention) ────────────────
        // Spec numbers in [start, end) after the dot are treated as TR/study items
        // (e.g. 23.700-XX, 36.7XX, 38.8XX). Used as fallback when the DB
        // doc_type column is not 'TR'.
        int studyReportRangeStart,
        int studyReportRangeEnd,

        // ── Resource paths (classpath: or file: prefixes supported) ─────
        String stopWordsPath,
        String andTermSubstPath,
        String non3gppIntentTermsPath,

        // Scope gate: marker → owning-spec table. A marker only fires when its
        // owning spec is absent from the index, so ingesting the spec disables it.
        String specOwnershipPath,

        // Intent classification by nearest labelled exemplar in embedding space.
        // The keyword classifier scored 41% on held-out paraphrases; this is the
        // generalising path. minSimilarity is the floor below which the embedding
        // answer is discarded in favour of the keyword rules.
        // ── getProcedureFlow ────────────────────────────────────────────
        // A layer with nothing relevant used to still contribute its top-N hits,
        // so the tool never reported a layer as empty and padded every bundle
        // with noise. A hit must now clear BOTH an absolute floor and a fraction
        // of the best score across all layers.
        double procedureLayerFloor,
        double procedureLayerRelativeFloor,
        String testSpecPrefixesPath,
        String procedureLayersPath,
        String procedureSynonymsPath,
        String seriesCatalogPath,
        // Boost applied to a layer's preferred spec family. A nudge, not a filter.
        double procedurePreferBoost,

        String intentsPath,
        String intentExemplarsPath,
        double intentMinSimilarity
) {}

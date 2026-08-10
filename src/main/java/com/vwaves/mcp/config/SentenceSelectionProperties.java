package com.vwaves.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for adaptive sentence-level extraction in {@code RerankService}.
 * Bound from {@code app.search.selection.*} in application.properties.
 *
 * <p>The selector returns a compact answer-focused subset of a retrieved
 * chunk. Defaults reflect the values that produced 0 hallucinations and
 * ≤1 major_loss / 20 on the telecom-verbosity benchmark on 2026-05-12; do
 * not change them without re-running {@code bench_verbosity.py}.
 *
 * <p>Two complete presets are exposed ({@code brief}, {@code normal}). Brief
 * is the aggressive-compression preset; normal is the default and is wider
 * on every knob. Both share the global thresholds in {@link Detection} and
 * {@link Enumeration} for shape detection and list-aware MMR bypass.
 */
@ConfigurationProperties(prefix = "app.search.selection")
public record SentenceSelectionProperties(
        Preset brief,
        Preset normal,
        double minScore,
        Detection detection,
        Enumeration enumeration
) {
    /**
     * @param maxSentences  hard cap on MMR picks (before window expansion)
     * @param scoreGap      adaptive band — sentences within this of top score are eligible
     * @param mmrLambda     0.0=pure diversity, 1.0=pure relevance (0.7 is a sane default)
     * @param window        neighbour sentences (±N) kept around each pick for context
     * @param fallbackChars char cap when the reranker is unavailable
     */
    public record Preset(
            int    maxSentences,
            double scoreGap,
            double mmrLambda,
            int    window,
            int    fallbackChars
    ) {}

    /**
     * Heuristics for classifying chunk shape (prose vs TOC/table). When a
     * chunk is detected as TABLE_OR_TOC the selector returns the whole text
     * capped at {@code tocCharMultiplier × preset.fallbackChars()} — sentence
     * extraction otherwise collapses TOC entries that aren't sentence-shaped.
     *
     * @param tocDotPatternRegex  regex that matches a TOC leader-dot run + page number
     * @param tocMinPatternHits   ≥N matches of the dot pattern => TABLE_OR_TOC
     * @param tocCharMultiplier   multiplier applied to fallbackChars for the cap
     * @param shortLineMaxChars   line length below which a line is "short"
     * @param shortLineRatioPct   ≥this% of non-empty lines short => TABLE_OR_TOC
     * @param shapeMinLines       minimum non-empty lines to attempt shape classification
     */
    public record Detection(
            String tocDotPatternRegex,
            int    tocMinPatternHits,
            int    tocCharMultiplier,
            int    shortLineMaxChars,
            int    shortLineRatioPct,
            int    shapeMinLines
    ) {}

    /**
     * Tuning for list-aware MMR bypass. When a chunk contains structurally
     * parallel sibling sentences (e.g. "Instantiate VNF / Query VNF / Scale
     * VNF / Check VNF / Heal VNF") MMR's diversity penalty wrongly treats
     * them as duplicates. Detection buckets sentences by their Nth token; if
     * any bucket holds ≥{@code minBucketCount} items, the selector relaxes
     * the score floor and switches to λ=1.0 (pure relevance).
     *
     * Brief mode skips the bypass when {@code preset.maxSentences} is below
     * {@code briefSkipMaxSentencesThreshold} — the consumer asked for
     * aggressive compression and can drill down for the full list.
     *
     * @param bucketTokenIndex                0-based token index used for bucketing (1 = second word)
     * @param minBucketCount                  bucket size needed to flag as enumeration
     * @param minSecondTokenLength            ignore tokens shorter than this (filters stop-words / punctuation)
     * @param maxPicksExtra                   add this to preset.maxSentences when bypass triggers
     * @param maxPicksFloor                   absolute floor on max picks under bypass
     * @param briefSkipMaxSentencesThreshold  presets with maxSentences below this opt out of bypass
     */
    public record Enumeration(
            int bucketTokenIndex,
            int minBucketCount,
            int minSecondTokenLength,
            int maxPicksExtra,
            int maxPicksFloor,
            int briefSkipMaxSentencesThreshold
    ) {}
}

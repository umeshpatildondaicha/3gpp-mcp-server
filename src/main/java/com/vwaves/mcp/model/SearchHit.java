package com.vwaves.mcp.model;

/**
 * One retrieved chunk.
 *
 * <p>{@code chunkId}, {@code docType} and {@code retrieverSupport} are carried
 * through the rerank stage so post-rerank logic (study-report discount,
 * release-aware boost, confidence scoring) can run without a second DB lookup.
 * They are nullable/zero for hits built by code paths that don't need them.
 *
 * @param chunkIndex position within the spec; restores document order, which for a
 *        procedure spec approximates step order. -1 when unknown.
 * @param retrieverSupport how many of the two retrievers ranked this chunk's spec
 *        in their own top tier: 0, 1 or 2. Two independent retrievers agreeing is
 *        corroboration that the score margin alone cannot express.
 */
public record SearchHit(
        double score,
        String specId,
        String release,
        String title,
        String seriesDesc,
        String snippet,
        String chunkId,
        String docType,
        int retrieverSupport,
        int chunkIndex
) {
    /** Legacy 6-arg shape, for call sites that carry no provenance. */
    public SearchHit(double score, String specId, String release,
                     String title, String seriesDesc, String snippet) {
        this(score, specId, release, title, seriesDesc, snippet, null, null, 0, -1);
    }

    /** Copy with a replaced score, preserving all provenance fields. */
    public SearchHit withScore(double newScore) {
        return new SearchHit(newScore, specId, release, title, seriesDesc,
                snippet, chunkId, docType, retrieverSupport, chunkIndex);
    }
}

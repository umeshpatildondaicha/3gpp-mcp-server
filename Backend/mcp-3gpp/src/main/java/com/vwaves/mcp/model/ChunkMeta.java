package com.vwaves.mcp.model;

/**
 * @param chunkIndex position of this chunk within its spec. Stored as TEXT in the
 *        DB; parsed to int here. -1 when absent or unparseable. Used to restore
 *        document order, which for a procedure spec approximates step order.
 */
public record ChunkMeta(
        String id,
        String specId,
        String release,
        String series,
        String seriesDesc,
        String docType,
        String title,
        int chunkIndex
) {
}

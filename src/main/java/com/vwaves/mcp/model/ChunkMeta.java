package com.vwaves.mcp.model;

public record ChunkMeta(
        long id,
        String specId,
        String release,
        String series,
        String seriesDesc,
        String docType,
        String title
) {
}

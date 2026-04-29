package com.vwaves.mcp.model;

public record ChunkMeta(
        String id,
        String specId,
        String release,
        String series,
        String seriesDesc,
        String docType,
        String title
) {
}

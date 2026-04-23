package com.vwaves.mcp.model;

public record SearchHit(
        double score,
        String specId,
        String release,
        String title,
        String seriesDesc,
        String snippet
) {
}

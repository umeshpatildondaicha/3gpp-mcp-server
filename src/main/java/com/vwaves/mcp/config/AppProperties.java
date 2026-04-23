package com.vwaves.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        long sessionTtlMs,
        String kbDbPath
) {
    public boolean hasLocalDbPath() {
        return nonEmpty(kbDbPath);
    }

    private boolean nonEmpty(String value) {
        return value != null && !value.isBlank();
    }
}

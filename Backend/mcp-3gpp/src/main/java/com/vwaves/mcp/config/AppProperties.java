package com.vwaves.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        long sessionTtlMs,
        String kbDbPath,
        String kbDbPath2
) {
    public boolean hasLocalDbPath() {
        return nonEmpty(kbDbPath);
    }

    public boolean hasLocalDbPath2() {
        return nonEmpty(kbDbPath2);
    }

    private boolean nonEmpty(String value) {
        return value != null && !value.isBlank();
    }
}

package com.vwaves.mcp.service;

import com.vwaves.mcp.config.AppProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DbResolver {
    private static final Logger log = LoggerFactory.getLogger(DbResolver.class);
    private final AppProperties appProperties;

    public DbResolver(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public Path resolveDb() {
        if (!appProperties.hasLocalDbPath()) {
            throw new IllegalStateException("KB_DB_PATH is required. This service is configured for local DB only.");
        }
        Path localPath = Path.of(appProperties.kbDbPath());
        if (!Files.exists(localPath)) {
            throw new IllegalStateException("DB file not found: " + localPath);
        }
        log.info("using local DB: {}", localPath);
        return localPath;
    }
}

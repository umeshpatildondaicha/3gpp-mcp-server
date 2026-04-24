package com.vwaves.mcp.service;

import com.vwaves.mcp.config.AppProperties;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

@Service
public class DbResolver {
    private static final Logger log = LoggerFactory.getLogger(DbResolver.class);
    private final AppProperties appProperties;
    private final ResourceLoader resourceLoader;

    public DbResolver(AppProperties appProperties, ResourceLoader resourceLoader) {
        this.appProperties = appProperties;
        this.resourceLoader = resourceLoader;
    }

    public Path resolveDb() {
        if (!appProperties.hasLocalDbPath()) {
            throw new IllegalStateException("KB_DB_PATH is required. This service is configured for local DB only.");
        }

        String dbPath = appProperties.kbDbPath();

        if (dbPath.startsWith("classpath:")) {
            return resolveClasspathDb(dbPath);
        }

        Path localPath = Path.of(dbPath);
        if (!Files.exists(localPath)) {
            throw new IllegalStateException("DB file not found: " + localPath);
        }
        log.info("using local DB: {}", localPath);
        return localPath;
    }

    private Path resolveClasspathDb(String classpathLocation) {
        try {
            Resource resource = resourceLoader.getResource(classpathLocation);
            if (!resource.exists()) {
                throw new IllegalStateException("Classpath DB resource not found: " + classpathLocation);
            }
            // If it's a plain file (dev/exploded), use it directly
            try {
                Path filePath = resource.getFile().toPath();
                log.info("using classpath DB (file): {}", filePath);
                return filePath;
            } catch (Exception ignored) {
                // Resource is inside a JAR — extract to temp file
            }
            Path tmp = Files.createTempFile("3gpp-", ".db");
            tmp.toFile().deleteOnExit();
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("extracted classpath DB to temp file: {}", tmp);
            return tmp;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve classpath DB: " + classpathLocation, e);
        }
    }
}

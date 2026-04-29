package com.vwaves.mcp.service;

import com.vwaves.mcp.config.AppProperties;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
        String dbPath = appProperties.hasLocalDbPath()
                ? appProperties.kbDbPath()
                : "classpath:3gpp.db";

        if (dbPath.startsWith("classpath:")) {
            return resolveClasspathDb(dbPath);
        }

        Path localPath = Path.of(dbPath);
        if (Files.exists(localPath) && isValidKbDb(localPath)) {
            log.info("using local DB: {}", localPath);
            return localPath;
        }

        // Configured filesystem path is missing or unusable — fall back to bundled
        // classpath DB so a stale KB_DB_PATH env var or broken sidecar file can't
        // crash the pod. The DB ships inside the JAR via classpath:3gpp.db.
        log.warn("Configured DB path {} not usable; falling back to classpath:3gpp.db", localPath);
        return resolveClasspathDb("classpath:3gpp.db");
    }

    private boolean isValidKbDb(Path path) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='chunks'")) {
            return rs.next();
        } catch (SQLException e) {
            log.warn("DB at {} failed validation: {}", path, e.getMessage());
            return false;
        }
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

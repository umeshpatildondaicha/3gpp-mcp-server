package com.vwaves.mcp.service;

import com.vwaves.mcp.config.AppProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
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

    private static final String PRIMARY_DB_CLASSPATH = "classpath:3gpp_certified.db";
    private static final String EXTRAS_DB_CLASSPATH = "classpath:telecom_extras.db";
    private static final String PRIMARY_DB_ENV = "KB_DB_PATH";
    private static final String EXTRAS_DB_ENV = "KB_DB_PATH2";

    private final AppProperties appProperties;
    private final ResourceLoader resourceLoader;

    public DbResolver(AppProperties appProperties, ResourceLoader resourceLoader) {
        this.appProperties = appProperties;
        this.resourceLoader = resourceLoader;
    }

    public Path resolveDb() {
        String configured = appProperties.hasLocalDbPath()
                ? appProperties.kbDbPath()
                : PRIMARY_DB_CLASSPATH;
        warnIfClasspathOverride(configured, PRIMARY_DB_ENV, PRIMARY_DB_CLASSPATH);
        return resolve(configured, PRIMARY_DB_CLASSPATH, PRIMARY_DB_ENV, "primary");
    }

    public Path resolveDb2() {
        String configured = appProperties.hasLocalDbPath2()
                ? appProperties.kbDbPath2()
                : EXTRAS_DB_CLASSPATH;
        warnIfClasspathOverride(configured, EXTRAS_DB_ENV, EXTRAS_DB_CLASSPATH);
        return resolve(configured, EXTRAS_DB_CLASSPATH, EXTRAS_DB_ENV, "extras");
    }

    /**
     * Catch the common misconfiguration where someone overrides {@code KB_DB_PATH}
     * to a {@code classpath:...} value. The classpath fallback only exists to host
     * a tiny fixture for unit tests; for real deployments the value must be a
     * filesystem path. Loud warning at startup so it shows up before the
     * downstream "DB not found" failure.
     */
    private void warnIfClasspathOverride(String configured, String envVar, String defaultClasspath) {
        if (configured == null) return;
        if (!configured.startsWith("classpath:")) return;
        if (configured.equals(defaultClasspath)) return; // unset / using default — fine
        log.warn("{} is set to '{}', a classpath: value. Classpath DBs are not packed in production builds; " +
                        "this will fail at startup. Set {} to an absolute filesystem path " +
                        "(e.g. /var/lib/3gpp-kb/3gpp_certified.db).",
                envVar, configured, envVar);
    }

    private Path resolve(String dbPath, String fallbackClasspath, String envVar, String label) {
        if (dbPath.startsWith("classpath:")) {
            return resolveClasspathDb(dbPath, envVar, label);
        }
        Path localPath = Path.of(dbPath);
        if (Files.exists(localPath) && isValidKbDb(localPath)) {
            log.info("using local DB: {}", localPath);
            return localPath;
        }
        if (!Files.exists(localPath)) {
            log.warn("DB at {} (env {}) does not exist; falling back to classpath", localPath, envVar);
        } else {
            log.warn("DB at {} (env {}) failed validation (no 'chunks' table); falling back to classpath",
                    localPath, envVar);
        }
        return resolveClasspathDb(fallbackClasspath, envVar, label);
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

    private Path resolveClasspathDb(String classpathLocation, String envVar, String label) {
        try {
            Resource resource = resourceLoader.getResource(classpathLocation);
            if (!resource.exists()) {
                throw new IllegalStateException(String.format(
                        "%s knowledge-base DB not found.%n" +
                        "  Tried classpath fallback: %s (not packed in the JAR — DBs are too large)%n" +
                        "  Set the env var %s to an absolute path of a valid SQLite DB " +
                        "containing a 'chunks' table.%n" +
                        "  Example:%n    export %s=/var/lib/3gpp-kb/%s%n" +
                        "  See README.production.md for full deployment instructions.",
                        label, classpathLocation, envVar, envVar,
                        envVar.equals(PRIMARY_DB_ENV) ? "3gpp_certified.db" : "telecom_extras.db"));
            }
            // If it's a plain file (dev/exploded), use it directly
            Path filePath = asFilePath(resource);
            if (filePath != null) {
                log.info("using classpath DB (file): {}", filePath);
                return filePath;
            }
            Path tmp = createPrivateTempFile();
            tmp.toFile().deleteOnExit();
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("extracted classpath DB to temp file: {}", tmp);
            return tmp;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve " + label + " DB at " + classpathLocation, e);
        }
    }

    /**
     * Scratch file for the JAR-extracted DB. Deliberately NOT in the shared system
     * temp dir (/tmp is world-writable, so another local user could swap the KB
     * between extraction and open) — it goes in a per-user directory under the
     * home dir, created 700/600 on POSIX filesystems.
     */
    private static Path createPrivateTempFile() throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), ".3gpp-kb", "extracted");
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
            return Files.createTempFile(dir, "3gpp-", ".db",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.createDirectories(dir);
        return Files.createTempFile(dir, "3gpp-", ".db");
    }

    /** Returns the resource's filesystem path, or {@code null} when it lives inside a JAR. */
    private static Path asFilePath(Resource resource) {
        try {
            return resource.getFile().toPath();
        } catch (Exception ignored) {
            // Resource is inside a JAR — caller extracts to a temp file
            return null;
        }
    }
}

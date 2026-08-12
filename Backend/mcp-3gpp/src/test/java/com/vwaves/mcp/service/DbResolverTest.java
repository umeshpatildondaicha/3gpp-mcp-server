package com.vwaves.mcp.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vwaves.mcp.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * DbResolver decides which SQLite file the whole server runs against, so a wrong
 * decision here is a silently-wrong knowledge base. The contract under test:
 *   - a configured local path wins ONLY if the file exists AND looks like a KB
 *     (has a 'chunks' table); anything else falls back to the classpath fixture
 *   - the classpath fixture is deliberately absent from this build, so every
 *     fallback must end in a loud IllegalStateException naming the env var to set
 * Tiny real SQLite DBs are created per test via @TempDir — never the 3.4GB KBs.
 */
class DbResolverTest {

    @TempDir
    Path tempDir;

    /** A real SQLite file containing (only) the table DbResolver validates against. */
    private Path newKbDb(String name) throws Exception {
        Path db = tempDir.resolve(name);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE chunks (id INTEGER PRIMARY KEY, text TEXT)");
        }
        return db;
    }

    /** A real SQLite file that is NOT a KB — no 'chunks' table. */
    private Path newNonKbDb(String name) throws Exception {
        Path db = tempDir.resolve(name);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE other (id INTEGER)");
        }
        return db;
    }

    private DbResolver resolver(String kbDbPath, String kbDbPath2) {
        return new DbResolver(new AppProperties(0L, kbDbPath, kbDbPath2),
                new DefaultResourceLoader());
    }

    @Nested
    @DisplayName("valid local DB paths are used directly")
    class ValidLocalPaths {

        @Test
        void resolveDbReturnsConfiguredFileWhenItHasAChunksTable() throws Exception {
            Path db = newKbDb("primary.db");
            Path resolved = resolver(db.toString(), null).resolveDb();
            assertEquals(db, resolved);
        }

        @Test
        void resolveDb2ReturnsConfiguredFileWhenItHasAChunksTable() throws Exception {
            Path db = newKbDb("extras.db");
            Path resolved = resolver(null, db.toString()).resolveDb2();
            assertEquals(db, resolved);
        }

        @Test
        void primaryAndExtrasResolveIndependently() throws Exception {
            Path primary = newKbDb("primary.db");
            Path extras = newKbDb("extras.db");
            DbResolver r = resolver(primary.toString(), extras.toString());
            assertEquals(primary, r.resolveDb());
            assertEquals(extras, r.resolveDb2());
        }
    }

    @Nested
    @DisplayName("bad local paths fall back — and the fallback fails loudly")
    class BadLocalPaths {

        @Test
        void missingFileFallsBackAndNamesTheEnvVar() {
            // The classpath fixture is not packed into this build, so the fallback
            // must throw a self-diagnosing error rather than return a bogus path.
            Path missing = tempDir.resolve("does-not-exist.db");
            DbResolver r = resolver(missing.toString(), null);
            IllegalStateException e = assertThrows(IllegalStateException.class, r::resolveDb);
            assertTrue(e.getMessage().contains("KB_DB_PATH"),
                    "error must tell the operator which env var to set: " + e.getMessage());
            assertTrue(e.getMessage().contains("3gpp_certified.db"),
                    "error must name the expected DB file: " + e.getMessage());
        }

        @Test
        void sqliteFileWithoutChunksTableIsRejected() throws Exception {
            // Exists and is valid SQLite, but is not a knowledge base.
            Path db = newNonKbDb("wrong-schema.db");
            DbResolver r = resolver(db.toString(), null);
            IllegalStateException e = assertThrows(IllegalStateException.class, r::resolveDb);
            assertTrue(e.getMessage().contains("KB_DB_PATH"));
        }

        @Test
        void nonSqliteGarbageFileIsRejected() throws Exception {
            Path db = tempDir.resolve("garbage.db");
            Files.writeString(db, "this is not a sqlite database");
            DbResolver r = resolver(db.toString(), null);
            assertThrows(IllegalStateException.class, r::resolveDb);
        }

        @Test
        void extrasFallbackNamesItsOwnEnvVarAndFile() {
            Path missing = tempDir.resolve("nope.db");
            DbResolver r = resolver(null, missing.toString());
            IllegalStateException e = assertThrows(IllegalStateException.class, r::resolveDb2);
            assertTrue(e.getMessage().contains("KB_DB_PATH2"),
                    "extras error must name KB_DB_PATH2, not KB_DB_PATH: " + e.getMessage());
            assertTrue(e.getMessage().contains("telecom_extras.db"));
        }
    }

    @Nested
    @DisplayName("classpath: configuration values")
    class ClasspathValues {

        @Test
        void unsetPathUsesTheClasspathDefaultWhichIsAbsentHere() {
            // kbDbPath null → hasLocalDbPath() false → classpath:3gpp_certified.db,
            // which is deliberately not on the test classpath (too large to pack).
            DbResolver r = resolver(null, null);
            IllegalStateException e = assertThrows(IllegalStateException.class, r::resolveDb);
            assertTrue(e.getMessage().contains("classpath:3gpp_certified.db"));
        }

        @Test
        void blankPathCountsAsUnset() {
            DbResolver r = resolver("   ", null);
            assertThrows(IllegalStateException.class, r::resolveDb);
        }

        @Test
        void explicitClasspathOverrideStillResolvesViaClasspathAndFailsWithItsName() {
            // The common misconfiguration: KB_DB_PATH=classpath:custom.db.
            DbResolver r = resolver("classpath:custom.db", null);
            IllegalStateException e = assertThrows(IllegalStateException.class, r::resolveDb);
            assertTrue(e.getMessage().contains("classpath:custom.db"),
                    "error must echo the bad configured value: " + e.getMessage());
        }
    }

    @Nested
    @DisplayName("classpath resource extraction")
    class ClasspathExtraction {

        /**
         * A classpath resource living inside a JAR has no File; DbResolver must
         * copy it to a temp file. ByteArrayResource reproduces exactly that shape:
         * exists() is true but getFile() throws.
         */
        @Test
        void jarBackedResourceIsExtractedToATempFile() throws Exception {
            byte[] content = "fake-db-bytes".getBytes(StandardCharsets.UTF_8);
            ResourceLoader jarLike = new ResourceLoader() {
                @Override
                public Resource getResource(String location) {
                    return new ByteArrayResource(content);
                }

                @Override
                public ClassLoader getClassLoader() {
                    return getClass().getClassLoader();
                }
            };
            DbResolver r = new DbResolver(new AppProperties(0L, null, null), jarLike);

            Path resolved = r.resolveDb();

            assertTrue(Files.exists(resolved), "extracted temp file must exist");
            assertArrayEquals(content, Files.readAllBytes(resolved),
                    "extracted file must be a byte-for-byte copy of the resource");
            assertTrue(resolved.getFileName().toString().endsWith(".db"));
        }

        @Test
        void fileBackedClasspathResourceIsUsedInPlaceWithoutCopying() throws Exception {
            // Dev/exploded layout: the resource IS a plain file — no temp copy.
            Path onDisk = tempDir.resolve("exploded.db");
            Files.writeString(onDisk, "exploded");
            ResourceLoader fileBacked = new ResourceLoader() {
                @Override
                public Resource getResource(String location) {
                    return new org.springframework.core.io.FileSystemResource(onDisk);
                }

                @Override
                public ClassLoader getClassLoader() {
                    return getClass().getClassLoader();
                }
            };
            DbResolver r = new DbResolver(new AppProperties(0L, null, null), fileBacked);

            assertEquals(onDisk.toAbsolutePath(), r.resolveDb().toAbsolutePath());
        }
    }
}

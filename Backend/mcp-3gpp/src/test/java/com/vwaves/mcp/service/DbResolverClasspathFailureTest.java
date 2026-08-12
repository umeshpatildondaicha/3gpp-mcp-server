package com.vwaves.mcp.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vwaves.mcp.config.AppProperties;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Covers the {@link DbResolver} branch {@code DbResolverTest} leaves out: a
 * classpath resource that EXISTS but cannot be extracted (its stream fails).
 * The failure must be wrapped in the generic "Failed to resolve" error — not
 * the friendlier not-found guidance, which would misdiagnose the problem.
 */
class DbResolverClasspathFailureTest {

    /** Jar-like resource: exists, has no filesystem file, and fails on read. */
    private static ResourceLoader unreadableResourceLoader() {
        return new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return new AbstractResource() {
                    @Override
                    public boolean exists() {
                        return true;
                    }

                    @Override
                    public String getDescription() {
                        return "unreadable " + location;
                    }

                    // getFile() inherits AbstractResource's FileNotFoundException,
                    // matching a genuine in-JAR resource.

                    @Override
                    public InputStream getInputStream() throws IOException {
                        throw new IOException("simulated stream failure");
                    }
                };
            }

            @Override
            public ClassLoader getClassLoader() {
                return getClass().getClassLoader();
            }
        };
    }

    @Test
    void unreadablePrimaryResourceIsWrappedInTheGenericResolveFailure() {
        DbResolver r = new DbResolver(new AppProperties(0L, null, null),
                unreadableResourceLoader());

        IllegalStateException e = assertThrows(IllegalStateException.class, r::resolveDb);

        assertTrue(e.getMessage().contains("Failed to resolve primary DB"),
                "extraction failure must use the generic wrapper: " + e.getMessage());
        assertTrue(e.getCause() instanceof IOException,
                "the underlying IO failure must be preserved as the cause");
    }

    @Test
    void unreadableExtrasResourceNamesTheExtrasLabel() {
        DbResolver r = new DbResolver(new AppProperties(0L, null, null),
                unreadableResourceLoader());

        IllegalStateException e = assertThrows(IllegalStateException.class, r::resolveDb2);

        assertTrue(e.getMessage().contains("Failed to resolve extras DB"),
                "extras failure must be labelled as extras: " + e.getMessage());
    }

    @Test
    void classpathOverrideOnTheExtrasPathAlsoFailsWithItsOwnName() {
        // KB_DB_PATH2=classpath:custom-extras.db — the misconfiguration warning
        // path for the EXTRAS side, then the loud not-found failure.
        DbResolver r = new DbResolver(new AppProperties(0L, null, "classpath:custom-extras.db"),
                new DefaultResourceLoader());

        IllegalStateException e = assertThrows(IllegalStateException.class, r::resolveDb2);

        assertTrue(e.getMessage().contains("classpath:custom-extras.db"),
                "error must echo the bad configured value: " + e.getMessage());
        assertTrue(e.getMessage().contains("KB_DB_PATH2"));
    }
}

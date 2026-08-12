package com.vwaves.mcp.service;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Test doubles shared by the IO-failure tests: a {@link Resource} that exists
 * but whose stream fails mid-read, and a {@link ResourceLoader} that hands it
 * out for every location. Exercises the {@code catch (IOException)} degradation
 * branches of every TSV/token loader without touching the filesystem.
 */
final class FailingResourceSupport {

    private FailingResourceSupport() {}

    /** exists() is true, getInputStream() succeeds, but reading throws. */
    static final class MidReadFailingResource extends AbstractResource {
        private final String description;

        MidReadFailingResource(String description) {
            this.description = description;
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public InputStream getInputStream() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("simulated mid-read failure for " + description);
                }
            };
        }
    }

    /** Returns a {@link MidReadFailingResource} for every location asked for. */
    static final class FailingResourceLoader implements ResourceLoader {
        @Override
        public Resource getResource(String location) {
            return new MidReadFailingResource(location);
        }

        @Override
        public ClassLoader getClassLoader() {
            return getClass().getClassLoader();
        }
    }
}

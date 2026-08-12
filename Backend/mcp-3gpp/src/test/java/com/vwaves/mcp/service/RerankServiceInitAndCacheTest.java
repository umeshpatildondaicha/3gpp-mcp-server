package com.vwaves.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vwaves.mcp.config.SentenceSelectionProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the {@link RerankService} lifecycle paths that never need an ONNX
 * model: the disabled early-return in init(), the graceful degradation when the
 * model source is unusable, and the cache-hit short-circuit of cachedDownload.
 *
 * <p>No network is ever touched: the URIs use schemes {@code java.net.http}
 * rejects at request-build time, and the cache-hit test pre-seeds the cache so
 * the download path is skipped entirely. {@code user.home} is redirected to a
 * temp dir for the cache tests and restored afterwards, so the real
 * {@code ~/.cache} is never written.
 */
class RerankServiceInitAndCacheTest {

    @TempDir
    Path fakeHome;

    private String originalUserHome;
    private SentenceSelectionProperties props;

    @BeforeEach
    void setUp() {
        originalUserHome = System.getProperty("user.home");
        props = new SentenceSelectionProperties(
                new SentenceSelectionProperties.Preset(4, 0.20, 0.7, 1, 350),
                new SentenceSelectionProperties.Preset(6, 0.25, 0.7, 1, 600),
                0.20,
                new SentenceSelectionProperties.Detection(
                        "\\.{4,}\\s*\\d+", 3, 2, 60, 70, 5),
                new SentenceSelectionProperties.Enumeration(
                        1, 3, 2, 3, 8, 5));
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
    }

    private RerankService service(String modelUri, String tokenizerUri, boolean enabled) {
        return new RerankService(modelUri, tokenizerUri, enabled, 128,
                props, new SimpleMeterRegistry());
    }

    /** Mirrors RerankService.shortHash: first 6 bytes of SHA-256, hex. */
    private static String shortHash(String url) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256")
                .digest(url.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
        return sb.toString();
    }

    private static Object invokeCachedDownload(String url, String prefix, String suffix)
            throws Exception {
        Method m = RerankService.class.getDeclaredMethod(
                "cachedDownload", String.class, String.class, String.class);
        m.setAccessible(true);
        return m.invoke(null, url, prefix, suffix);
    }

    // ── init() lifecycle ─────────────────────────────────────────────────────

    @Nested
    class InitLifecycle {

        @Test
        void disabled_initReturnsImmediatelyWithoutTouchingStartupPhase() {
            RerankService svc = service("file:///unused", "file:///unused", false);
            StartupState state = new StartupState();

            svc.init(state);

            assertThat(svc.isReady()).isFalse();
            assertThat(state.phase())
                    .as("disabled init must return before setting any phase")
                    .isEqualTo("initializing");
        }

        @Test
        void enabled_unusableModelSource_degradesInsteadOfThrowing() {
            // file:// is not an http(s) scheme, so the download request is
            // rejected locally before any network I/O — init must swallow that
            // and leave the service in the not-ready (search-without-rerank) state.
            System.setProperty("user.home", fakeHome.toString());
            RerankService svc = service("file:///no/such/model.onnx",
                    "file:///no/such/tokenizer.json", true);
            StartupState state = new StartupState();

            svc.init(state);

            assertThat(svc.isReady()).isFalse();
            assertThat(state.phase())
                    .as("enabled init must have reported its phase before failing")
                    .isEqualTo("loading-reranker");
            assertThat(fakeHome.resolve(".cache/3gpp-mcp"))
                    .as("the cache directory is created before the failure")
                    .isDirectory();
        }
    }

    // ── cachedDownload cache keying ──────────────────────────────────────────

    @Nested
    class CachedDownload {

        @Test
        void existingNonEmptyCacheFileShortCircuitsTheDownload() throws Exception {
            System.setProperty("user.home", fakeHome.toString());
            // The URL is never contacted: an .invalid TLD guarantees the test
            // fails loudly if the short-circuit ever regresses.
            String url = "https://model-host.invalid/tokenizer.json";
            Path cacheDir = fakeHome.resolve(".cache/3gpp-mcp");
            Files.createDirectories(cacheDir);
            Path cached = cacheDir.resolve("reranker-tokenizer-" + shortHash(url) + ".json");
            Files.writeString(cached, "cached-bytes");

            Object result = invokeCachedDownload(url, "reranker-tokenizer", ".json");

            assertThat(result).isEqualTo(cached);
            assertThat(Files.readString(cached))
                    .as("a cache hit must not rewrite the file")
                    .isEqualTo("cached-bytes");
        }

        @Test
        void emptyCacheFileIsNotTrustedAndTriggersTheDownloadPath() throws Exception {
            System.setProperty("user.home", fakeHome.toString());
            String url = "file:///not-http/tokenizer.json";
            Path cacheDir = fakeHome.resolve(".cache/3gpp-mcp");
            Files.createDirectories(cacheDir);
            // Zero bytes — a truncated previous download must be re-fetched.
            Files.createFile(cacheDir.resolve(
                    "reranker-tokenizer-" + shortHash(url) + ".json"));

            // The re-download is attempted (and rejected locally: not http/https),
            // proving the empty file did NOT short-circuit.
            assertThatThrownBy(() -> invokeCachedDownload(url, "reranker-tokenizer", ".json"))
                    .isInstanceOf(InvocationTargetException.class)
                    .cause().isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void cacheKeyIncludesTheUrlSoDifferentUrlsNeverCollide() throws Exception {
            System.setProperty("user.home", fakeHome.toString());
            String urlA = "https://host.invalid/model-A.onnx";
            String urlB = "https://host.invalid/model-B.onnx";
            Path cacheDir = fakeHome.resolve(".cache/3gpp-mcp");
            Files.createDirectories(cacheDir);
            Path cachedA = cacheDir.resolve("reranker-model-" + shortHash(urlA) + ".onnx");
            Path cachedB = cacheDir.resolve("reranker-model-" + shortHash(urlB) + ".onnx");
            Files.writeString(cachedA, "weights-A");
            Files.writeString(cachedB, "weights-B");

            assertThat(invokeCachedDownload(urlA, "reranker-model", ".onnx")).isEqualTo(cachedA);
            assertThat(invokeCachedDownload(urlB, "reranker-model", ".onnx")).isEqualTo(cachedB);
        }
    }
}

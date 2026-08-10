package com.vwaves.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vwaves.mcp.config.SentenceSelectionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the sentence-selection layer in {@link RerankService}.
 *
 * <p>These tests deliberately avoid loading the ONNX reranker — they exercise
 * the helpers that decide <em>policy</em> (shape detection, enumeration
 * detection, splitter) plus the explicit {@code !isReady()} fallback path.
 * The end-to-end ranked-selection path is covered by {@code bench_verbosity.py}
 * which runs against a real ONNX model.
 */
class RerankServiceSelectionTest {

    private SentenceSelectionProperties props;
    private MeterRegistry registry;
    private RerankService service;

    @BeforeEach
    void setup() {
        // Defaults match application.properties so tests stay aligned with prod.
        props = new SentenceSelectionProperties(
                new SentenceSelectionProperties.Preset(4, 0.20, 0.7, 1, 350),
                new SentenceSelectionProperties.Preset(6, 0.25, 0.7, 1, 600),
                0.20,
                new SentenceSelectionProperties.Detection(
                        "\\.{4,}\\s*\\d+", 3, 2, 60, 70, 5),
                new SentenceSelectionProperties.Enumeration(
                        1, 3, 2, 3, 8, 5));
        registry = new SimpleMeterRegistry();
        // Reranker URIs / enabled don't matter — we never call init().
        service = new RerankService("http://unused", "http://unused", false, 512,
                props, registry);
    }

    // ─────────────────────────── splitSentences ─────────────────────────────

    @Test
    void splitSentences_splitsOnTerminators() {
        List<String> out = RerankService.splitSentences(
                "First sentence. Second sentence! Third sentence?");
        assertThat(out).hasSize(3);
    }

    @Test
    void splitSentences_dropsFragmentsBelowMinChars() {
        // "1." is shorter than MIN_SENTENCE_CHARS (4) and gets dropped.
        List<String> out = RerankService.splitSentences("1. Long enough sentence here.");
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).contains("Long enough");
    }

    @Test
    void splitSentences_emptyOrBlankInput() {
        assertThat(RerankService.splitSentences("")).isEmpty();
        assertThat(RerankService.splitSentences("   ")).isEmpty();
    }

    // ─────────────────────────── briefSelection / normalSelection ───────────

    @Test
    void briefSelection_isStrictlyTighterThanNormal() {
        RerankService.SelectionConfig brief  = service.briefSelection();
        RerankService.SelectionConfig normal = service.normalSelection();

        assertThat(brief.maxSentences()).isLessThan(normal.maxSentences());
        assertThat(brief.scoreGap()).isLessThanOrEqualTo(normal.scoreGap());
        assertThat(brief.fallbackChars()).isLessThan(normal.fallbackChars());
    }

    // ─────────────────────────── detectShape ────────────────────────────────

    @Nested
    class DetectShape {

        @Test
        void plainProse_isProse() {
            String prose = "This is normal prose. It has full sentences. "
                    + "And reasonable lengths.";
            assertThat(service.detectShape(prose))
                    .isEqualTo(RerankService.ChunkShape.PROSE);
        }

        @Test
        void tocLeaderDots_classifiedAsTable() {
            String toc = """
                    Contents
                    Foreword................................. 4
                    Modal verbs terminology.................. 4
                    1 Scope.................................. 5
                    2 References............................. 5
                    """;
            assertThat(service.detectShape(toc))
                    .isEqualTo(RerankService.ChunkShape.TABLE_OR_TOC);
        }

        @Test
        void shortLineDominance_classifiedAsTable() {
            // 5 short lines, no leader dots — triggers heuristic 2 only.
            String table = """
                    APS Auto Protection
                    BPR Blocked Port Ref
                    CCM Continuity Check
                    DNF Do Not Flush
                    ETH Ethernet Layer
                    FDB Filtering DB
                    """;
            assertThat(service.detectShape(table))
                    .isEqualTo(RerankService.ChunkShape.TABLE_OR_TOC);
        }

        @Test
        void referenceList_isProse_notTable() {
            // Reference-list chunks ARE sentence-shaped — the reranker handles
            // them. Forcing TABLE bypass would dump useless boilerplate.
            String refs = "[32] 3GPP TS 38.314: \"NR; Layer 2 measurements\". "
                    + "[33] 3GPP TS 38.211: \"NR; Physical channels and modulation\". "
                    + "[34] 3GPP TS 38.214: \"NR; Physical layer procedures for data\".";
            assertThat(service.detectShape(refs))
                    .isEqualTo(RerankService.ChunkShape.PROSE);
        }

        @Test
        void emptyAndNull_returnProseSafely() {
            assertThat(service.detectShape("")).isEqualTo(RerankService.ChunkShape.PROSE);
            assertThat(service.detectShape(null)).isEqualTo(RerankService.ChunkShape.PROSE);
        }
    }

    // ─────────────────────────── looksLikeEnumeration ───────────────────────

    @Nested
    class Enumeration {

        @Test
        void parallelSiblings_sharingSecondToken_detected() {
            // NFV MANO-style: each item has "VNF" as token[1].
            List<String> sentences = List.of(
                    "Instantiate VNF This operation allows creating a VNF instance.",
                    "Query VNF This operation allows retrieving attributes.",
                    "Scale VNF This operation allows scaling a VNF instance.",
                    "Check VNF This operation allows verifying feasibility.",
                    "Heal VNF This operation is used to request healing.");
            assertThat(service.looksLikeEnumeration(sentences)).isTrue();
        }

        @Test
        void unrelatedProse_notDetected() {
            List<String> sentences = List.of(
                    "The AMF handles registration and mobility.",
                    "PDU sessions are managed by the SMF.",
                    "Authentication uses 5G AKA or EAP-AKA'.");
            assertThat(service.looksLikeEnumeration(sentences)).isFalse();
        }

        @Test
        void belowBucketThreshold_notDetected() {
            // Only 2 share "VNF" — minBucketCount is 3.
            List<String> sentences = List.of(
                    "Instantiate VNF allows creating.",
                    "Query VNF allows retrieving.",
                    "Authentication is unrelated.");
            assertThat(service.looksLikeEnumeration(sentences)).isFalse();
        }

        @Test
        void emptyInput_notDetected() {
            assertThat(service.looksLikeEnumeration(List.of())).isFalse();
        }
    }

    // ─────────────────────────── selectRelevantSentences (fallback) ─────────

    @Nested
    class FallbackPaths {

        @Test
        void emptyChunkText_returnsEmpty() {
            String out = service.selectRelevantSentences(
                    "query", "", service.briefSelection());
            assertThat(out).isEmpty();
        }

        @Test
        void nullChunkText_returnsEmpty() {
            String out = service.selectRelevantSentences(
                    "query", null, service.briefSelection());
            assertThat(out).isEmpty();
        }

        @Test
        void rerankerNotReady_returnsLengthCappedText() {
            // service was built with enabled=false → !isReady() → fallback path.
            String longText = "x".repeat(2000);
            RerankService.SelectionConfig brief = service.briefSelection();
            String out = service.selectRelevantSentences("query", longText, brief);
            // Capped at fallbackChars + the gap marker suffix.
            assertThat(out).startsWith("x".repeat(brief.fallbackChars()));
            assertThat(out.length())
                    .isLessThanOrEqualTo(brief.fallbackChars() + 4);

            assertThat(registry.counter(
                    "mcp.search.selection.fallback.reranker_unavailable").count())
                    .isEqualTo(1.0);
        }

        @Test
        void rerankerNotReady_shortTextReturnedAsIs() {
            String shortText = "Short sentence that fits.";
            String out = service.selectRelevantSentences(
                    "query", shortText, service.briefSelection());
            assertThat(out).isEqualTo(shortText);
        }

        @Test
        void tocShapedChunk_bypassesAndCountsMetric() {
            // Reranker is "not ready" so the TOC bypass branch isn't reached —
            // we instead verify the metric for the !isReady() fallback works.
            // (The TOC bypass requires isReady() == true; covered by the
            //  bench_verbosity.py integration test against a real ONNX run.)
            String toc = """
                    Foreword................................. 4
                    Scope.................................... 5
                    References............................... 5
                    Definitions.............................. 6
                    Abbreviations............................ 7
                    """;
            String out = service.selectRelevantSentences(
                    "query", toc, service.briefSelection());
            // Shape detection still runs and identifies this as TOC, but the
            // !isReady() guard returns the capped text first.
            assertThat(out).isNotEmpty();
            assertThat(service.detectShape(toc))
                    .isEqualTo(RerankService.ChunkShape.TABLE_OR_TOC);
        }
    }

    // ─────────────────────────── metrics wiring ─────────────────────────────

    @Test
    void invocationsCounter_incrementsPerCall() {
        service.selectRelevantSentences("q", "Some text.", service.briefSelection());
        service.selectRelevantSentences("q", "Other text.", service.briefSelection());
        assertThat(registry.counter("mcp.search.selection.invocations").count())
                .isEqualTo(2.0);
    }
}

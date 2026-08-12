package com.vwaves.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vwaves.mcp.config.SentenceSelectionProperties;
import com.vwaves.mcp.model.SearchHit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Covers the parts of {@link RerankService} that {@code RerankServiceSelectionTest}
 * does not: the {@link RerankService#rerank} degraded path (reranker never
 * initialised), the {@link RerankService.SelectionConfig} preset mapping, and
 * extra edge cases of the enumeration / shape heuristics.
 *
 * <p>No ONNX model is ever loaded — the service is built with enabled=false and
 * init() is never called, so isReady() is false throughout.
 */
class RerankServiceRerankPathTest {

    private SentenceSelectionProperties props;
    private RerankService service;

    @BeforeEach
    void setup() {
        // Same values as RerankServiceSelectionTest / application.properties.
        props = new SentenceSelectionProperties(
                new SentenceSelectionProperties.Preset(4, 0.20, 0.7, 1, 350),
                new SentenceSelectionProperties.Preset(6, 0.25, 0.7, 1, 600),
                0.20,
                new SentenceSelectionProperties.Detection(
                        "\\.{4,}\\s*\\d+", 3, 2, 60, 70, 5),
                new SentenceSelectionProperties.Enumeration(
                        1, 3, 2, 3, 8, 5));
        service = new RerankService("http://unused", "http://unused", false, 512,
                props, new SimpleMeterRegistry());
    }

    private static SearchHit hit(double score, String specId, String chunkId) {
        return new SearchHit(score, specId, "Rel-18", "title-" + specId, "NR",
                "snippet " + chunkId, chunkId, "TS", 1, 0);
    }

    // ── rerank() with the reranker unavailable ───────────────────────────────

    @Nested
    class RerankDegradedPath {

        @Test
        void notReady_returnsFirstTopKInOriginalOrderWithOriginalScores() {
            List<SearchHit> candidates = List.of(
                    hit(0.31, "23.501", "0:a"),
                    hit(0.98, "38.331", "0:b"),   // higher score but rank 2 —
                    hit(0.55, "24.501", "0:c"),   // degraded path must NOT re-sort
                    hit(0.10, "38.211", "0:d"));

            List<SearchHit> out = service.rerank("any query", candidates, 2);

            assertThat(out).hasSize(2);
            assertThat(out.get(0)).isEqualTo(candidates.get(0));
            assertThat(out.get(1)).isEqualTo(candidates.get(1));
        }

        @Test
        void notReady_topKLargerThanCandidateList_returnsAll() {
            List<SearchHit> candidates = List.of(hit(0.5, "23.501", "0:a"));
            List<SearchHit> out = service.rerank("q", candidates, 10);
            assertThat(out).containsExactlyElementsOf(candidates);
        }

        @Test
        void notReady_emptyCandidates_returnsEmpty() {
            assertThat(service.rerank("q", List.of(), 5)).isEmpty();
        }

        @Test
        void notReady_zeroTopK_returnsEmpty() {
            List<SearchHit> candidates = List.of(hit(0.5, "23.501", "0:a"));
            assertThat(service.rerank("q", candidates, 0)).isEmpty();
        }

        @Test
        void notReady_returnsAnIndependentMutableCopy() {
            List<SearchHit> candidates = new ArrayList<>(List.of(
                    hit(0.5, "23.501", "0:a"), hit(0.4, "38.331", "0:b")));
            List<SearchHit> out = service.rerank("q", candidates, 2);

            // The contract is a fresh list — mutating it must not touch the input.
            out.add(hit(0.1, "24.501", "0:z"));
            assertThat(candidates).hasSize(2);
        }

        @Test
        void isReady_isFalseWhenNeverInitialised() {
            assertThat(service.isReady()).isFalse();
        }
    }

    // ── SelectionConfig preset mapping ───────────────────────────────────────

    @Nested
    class SelectionConfigMapping {

        @Test
        void fromProps_copiesEveryPresetKnobAndTheGlobalMinScore() {
            SentenceSelectionProperties.Preset preset =
                    new SentenceSelectionProperties.Preset(7, 0.33, 0.61, 2, 480);

            RerankService.SelectionConfig cfg =
                    RerankService.SelectionConfig.fromProps(preset, 0.17);

            assertThat(cfg.maxSentences()).isEqualTo(7);
            assertThat(cfg.scoreGap()).isEqualTo(0.33);
            assertThat(cfg.mmrLambda()).isEqualTo(0.61);
            assertThat(cfg.window()).isEqualTo(2);
            assertThat(cfg.fallbackChars()).isEqualTo(480);
            assertThat(cfg.minScore()).isEqualTo(0.17);
        }

        @Test
        void briefSelection_reflectsTheBriefPresetExactly() {
            RerankService.SelectionConfig brief = service.briefSelection();
            assertThat(brief.maxSentences()).isEqualTo(4);
            assertThat(brief.scoreGap()).isEqualTo(0.20);
            assertThat(brief.mmrLambda()).isEqualTo(0.7);
            assertThat(brief.window()).isEqualTo(1);
            assertThat(brief.fallbackChars()).isEqualTo(350);
            assertThat(brief.minScore()).isEqualTo(0.20);
        }

        @Test
        void normalSelection_reflectsTheNormalPresetExactly() {
            RerankService.SelectionConfig normal = service.normalSelection();
            assertThat(normal.maxSentences()).isEqualTo(6);
            assertThat(normal.scoreGap()).isEqualTo(0.25);
            assertThat(normal.fallbackChars()).isEqualTo(600);
            assertThat(normal.minScore()).isEqualTo(0.20);
        }
    }

    // ── looksLikeEnumeration — bucketing edge cases ──────────────────────────

    @Nested
    class EnumerationEdgeCases {

        @Test
        void punctuationAndCaseAreNormalisedIntoOneBucket() {
            // "VNF." / "vnf," / "VNF;" must all land in the "vnf" bucket.
            List<String> sentences = List.of(
                    "Instantiate VNF. This creates an instance.",
                    "Query vnf, retrieving attributes.",
                    "Scale VNF; adjusts capacity.");
            assertThat(service.looksLikeEnumeration(sentences)).isTrue();
        }

        @Test
        void singleWordSentencesAreSkippedNotCrashed() {
            // token index 1 does not exist for one-word sentences.
            List<String> sentences = List.of("Alpha", "Beta", "Gamma", "Delta");
            assertThat(service.looksLikeEnumeration(sentences)).isFalse();
        }

        @Test
        void secondTokensBelowMinLengthAreIgnored() {
            // minSecondTokenLength=2: a single-letter second token never buckets.
            List<String> sentences = List.of(
                    "Step a first action.",
                    "Step a second action.",
                    "Step a third action.");
            // second token is "a" (length 1 < 2) — never counted, so no bucket
            // reaches minBucketCount even though all three share it.
            assertThat(service.looksLikeEnumeration(sentences)).isFalse();
        }
    }

    // ── detectShape — boundary cases ─────────────────────────────────────────

    @Nested
    class DetectShapeBoundaries {

        @Test
        void fewDotRunsAndFewLines_staysProse() {
            // Only 2 leader-dot hits (< tocMinPatternHits=3) and only 2 lines
            // (< shapeMinLines=5): neither heuristic may fire.
            String text = "Scope.................................. 5\n"
                    + "References............................. 5";
            assertThat(service.detectShape(text))
                    .isEqualTo(RerankService.ChunkShape.PROSE);
        }

        @Test
        void manyLongLines_staysProse() {
            // 6 lines, all longer than shortLineMaxChars=60 — ratio heuristic
            // must not fire on ordinary hard-wrapped prose.
            String line = "The UE shall apply the received configuration and confirm "
                    + "completion to the network using the RRCReconfigurationComplete message.";
            String text = (line + "\n").repeat(6);
            assertThat(service.detectShape(text))
                    .isEqualTo(RerankService.ChunkShape.PROSE);
        }

        @Test
        void blankLinesDoNotCountTowardShortLineRatio() {
            // 3 short lines separated by blanks: only 3 non-empty lines in total,
            // below shapeMinLines=5 even though the raw split yields more lines.
            String text = "AMF Core\n\nSMF Core\n\nUPF Core\n\n";
            assertThat(service.detectShape(text))
                    .isEqualTo(RerankService.ChunkShape.PROSE);
        }
    }
}

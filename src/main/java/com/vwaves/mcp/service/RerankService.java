package com.vwaves.mcp.service;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.vwaves.mcp.config.SentenceSelectionProperties;
import com.vwaves.mcp.model.SearchHit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.BreakIterator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Stage-2 cross-encoder reranker using BAAI/bge-reranker-v2-m3 (ONNX quantized).
 *
 * Pipeline:
 *   Retrieval (dense + BM25 → RRF, RERANK_CANDIDATES=50 pool)
 *   → Rerank  (cross-encoder scores each (query, chunk) pair)
 *   → Return top-K with reranker scores replacing RRF scores
 *
 * Dependencies already on classpath via spring-ai-starter-model-transformers:
 *   - com.microsoft.onnxruntime:onnxruntime:1.20.0  (ai.onnxruntime.*)
 *   - ai.djl.huggingface:tokenizers:0.32.0           (HuggingFaceTokenizer)
 *
 * Models are downloaded once to {@code ~/.cache/3gpp-mcp/} and reused on
 * subsequent startups. Total download: ~350 MB (quantized INT8 model).
 */
@Service
public class RerankService {
    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private static final String CACHE_DIR_NAME = ".cache/3gpp-mcp";
    private static final int    BATCH_SIZE     = 12;
    // Number of CPU threads given to ONNX Runtime for matrix operations.
    // More threads = faster inference, but diminishing returns above physical cores.
    private static final int    ONNX_INTRA_THREADS = Math.max(4,
            Runtime.getRuntime().availableProcessors() / 2);
    // Threads ONNX Runtime uses to run independent graph nodes in parallel.
    private static final int    ONNX_INTER_THREADS = 2;

    /** Rounds reranker scores to 4 decimal places (multiply, round, divide). */
    private static final double SCORE_ROUNDING_FACTOR = 10_000.0;

    /** Scale factor for integer percentage arithmetic (avoids float division). */
    private static final int PERCENT_SCALE = 100;

    // ── Model download ────────────────────────────────────────────────────────
    private static final int DOWNLOAD_CONNECT_TIMEOUT_SECONDS = 30;
    private static final int DOWNLOAD_REQUEST_TIMEOUT_MINUTES = 10;
    private static final int HTTP_OK = 200;

    /** Number of SHA-256 bytes kept for the cache-key digest. */
    private static final int SHORT_HASH_BYTES = 6;
    /** Hex length of the digest: two characters per byte. */
    private static final int SHORT_HASH_HEX_CHARS = 12;

    private final String  modelUri;
    private final String  tokenizerUri;
    private final boolean enabled;
    private final int     maxLength;
    private final SentenceSelectionProperties selectionProps;
    private final MeterRegistry meterRegistry;
    private final Pattern tocDotPattern;
    private final Timer selectionTimer;

    private HuggingFaceTokenizer tokenizer;
    private OrtEnvironment       ortEnv;
    private OrtSession           ortSession;
    private Set<String>          inputNames;

    public RerankService(
            @Value("${app.reranker.model-uri:https://huggingface.co/Xenova/bge-reranker-v2-m3/resolve/main/onnx/model_quantized.onnx}") String modelUri,
            @Value("${app.reranker.tokenizer-uri:https://huggingface.co/Xenova/bge-reranker-v2-m3/resolve/main/tokenizer.json}") String tokenizerUri,
            @Value("${app.reranker.enabled:true}") boolean enabled,
            @Value("${app.reranker.max-length:512}") int maxLength,
            SentenceSelectionProperties selectionProps,
            MeterRegistry meterRegistry
    ) {
        this.modelUri        = modelUri;
        this.tokenizerUri    = tokenizerUri;
        this.enabled         = enabled;
        this.maxLength       = maxLength;
        this.selectionProps  = selectionProps;
        this.meterRegistry   = meterRegistry;
        this.tocDotPattern   = Pattern.compile(selectionProps.detection().tocDotPatternRegex());
        this.selectionTimer  = Timer.builder("mcp.search.selection.duration")
                .description("Latency of sentence-level answer extraction per chunk")
                .register(meterRegistry);
    }

    /** Build a brief preset from the configured defaults. */
    public SelectionConfig briefSelection() {
        return SelectionConfig.fromProps(selectionProps.brief(), selectionProps.minScore());
    }

    /** Build a normal preset from the configured defaults. */
    public SelectionConfig normalSelection() {
        return SelectionConfig.fromProps(selectionProps.normal(), selectionProps.minScore());
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void init(StartupState startupState) {
        if (!enabled) {
            log.info("Reranker disabled (app.reranker.enabled=false)");
            return;
        }
        startupState.phase("loading-reranker");
        long t0 = System.currentTimeMillis();
        try {
            Path tokenizerPath = cachedDownload(tokenizerUri, "reranker-tokenizer", ".json");
            tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath, Map.of(
                    "maxLength",  String.valueOf(maxLength),
                    "truncation", "true",
                    "padding",    "true"
            ));

            Path modelPath = cachedDownload(modelUri, "reranker-model", ".onnx");
            ortEnv = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
                opts.setIntraOpNumThreads(ONNX_INTRA_THREADS);
                opts.setInterOpNumThreads(ONNX_INTER_THREADS);
                ortSession = ortEnv.createSession(modelPath.toString(), opts);
            }
            log.info("Reranker ONNX session created ({} intra-op threads)", ONNX_INTRA_THREADS);
            inputNames = ortSession.getInputNames();
            log.debug("Reranker ONNX inputs: {}", inputNames);

            float warmup = score("warmup query text", "warmup passage for cross-encoder init");
            if (log.isInfoEnabled()) {
                log.info("Reranker ready: {} (max-length={}, warmup score={}, {} ms)",
                        modelUri, maxLength, String.format("%.4f", warmup),
                        System.currentTimeMillis() - t0);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Reranker init interrupted — search proceeds without reranking.");
            ortSession = null;
            tokenizer  = null;
        } catch (Exception e) {
            log.warn("Reranker failed to initialize — search proceeds without reranking. Cause: {}",
                    e.getMessage());
            ortSession = null;
            tokenizer  = null;
        }
    }

    public boolean isReady() {
        return ortSession != null && tokenizer != null;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Reranks {@code candidates} using cross-encoder scoring and returns the
     * best {@code topK} hits. Each hit's score is replaced with the reranker's
     * sigmoid score in [0, 1].
     */
    public List<SearchHit> rerank(String query, List<SearchHit> candidates, int topK) {
        if (!isReady() || candidates.isEmpty()) {
            int limit = Math.min(topK, candidates.size());
            return new ArrayList<>(candidates.subList(0, limit));
        }

        List<ScoredHit> scored = scoreBatched(query, candidates);
        scored.sort(Comparator.comparingDouble(ScoredHit::score).reversed());

        int limit = Math.min(topK, scored.size());
        List<SearchHit> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            ScoredHit sh   = scored.get(i);
            double rounded = Math.round(sh.score() * SCORE_ROUNDING_FACTOR) / SCORE_ROUNDING_FACTOR;
            // withScore keeps chunkId / docType / denseScore, which the caller
            // needs for the study-report discount, release boost and vector rescue.
            out.add(sh.hit().withScore(rounded));
        }
        return out;
    }

    /**
     * Adaptive answer-focused extraction: returns only the sentences in
     * {@code chunkText} that are most relevant to {@code query}, joined back
     * into a compact passage.
     *
     * Pipeline:
     *   1. Sentence-split via {@link BreakIterator} (handles common abbreviations).
     *   2. Cross-encoder score each sentence vs. query (single ONNX batch).
     *   3. Filter by adaptive threshold: keep sentences within {@code scoreGap}
     *      of the top score and above {@code minScore}. This self-adjusts to
     *      question complexity instead of using a fixed top-N.
     *   4. Re-rank kept sentences by MMR (Maximal Marginal Relevance) so the
     *      picks cover different aspects of the question rather than echoing
     *      the same point.
     *   5. Expand each pick to include ±{@code window} neighbor sentences for
     *      surrounding context.
     *   6. Emit picks in document order. Where a gap is elided, insert " … "
     *      so the reader knows context was clipped (recoverable via getSpecInfo).
     *
     * Falls back to the original text (length-capped to ~600 chars) when the
     * reranker is unavailable, when the chunk has 0–1 sentences, or when no
     * sentence clears the score floor.
     */
    public String selectRelevantSentences(String query, String chunkText, SelectionConfig cfg) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            meterRegistry.counter(METRIC_INVOCATIONS).increment();
            return doSelect(query, chunkText, cfg);
        } finally {
            sample.stop(selectionTimer);
        }
    }

    private String doSelect(String query, String chunkText, SelectionConfig cfg) {
        if (chunkText == null || chunkText.isBlank()) return "";

        // Detect shape on the raw text (preserves newlines) before whitespace
        // normalization — TOC / abbreviation tables need a different policy.
        ChunkShape shape = detectShape(chunkText);

        String text = chunkText.replaceAll("\\s+", " ").strip();
        if (!isReady()) {
            meterRegistry.counter(METRIC_FALLBACK_RERANKER).increment();
            return capText(text, cfg.fallbackChars());
        }
        if (shape == ChunkShape.TABLE_OR_TOC) {
            // Sentence-level extraction collapses TOCs and abbreviation tables
            // because their entries aren't sentence-shaped. Hand back the chunk
            // capped at N× the fallback budget — the consumer's drill-down hint
            // still allows getSpecInfo recovery.
            meterRegistry.counter(METRIC_BYPASS_TOC).increment();
            int cap = cfg.fallbackChars() * selectionProps.detection().tocCharMultiplier();
            return capText(text, cap);
        }

        List<String> sentences = splitSentences(text);
        if (sentences.size() <= 1) {
            meterRegistry.counter(METRIC_FALLBACK_SHORT_CHUNK).increment();
            return text;
        }

        float[] scores = scoreTexts(query, sentences);
        List<Integer> picked = pickSentences(sentences, scores, cfg);
        TreeSet<Integer> windowed = expandWindows(picked, cfg.window(), sentences.size());
        return joinInDocumentOrder(sentences, windowed);
    }

    /**
     * Chooses the sentence indices to keep: adaptive score floor, list-aware
     * enumeration bypass, then MMR selection.
     */
    private List<Integer> pickSentences(List<String> sentences, float[] scores,
                                        SelectionConfig cfg) {
        float top = 0f;
        for (float s : scores) if (s > top) top = s;

        // List-aware bypass: detect on the FULL sentence pool, not just the
        // eligible subset. With normal's tight scoreGap, list items would be
        // filtered out before detection could see them — leaving only the
        // top-scoring 2 of a 5-item list. Detect first, then choose the floor.
        //
        // Brief mode opts out via briefSkipMaxSentencesThreshold — the consumer
        // asked for aggressive compression; if they need the full list they can
        // re-run with verbosity=normal/full or call getSpecInfo.
        boolean briefSkip = cfg.maxSentences()
                < selectionProps.enumeration().briefSkipMaxSentencesThreshold();
        boolean isEnumeration = !briefSkip && looksLikeEnumeration(sentences);

        // When an enumeration is detected, relax the score floor to the
        // absolute minimum so all list items survive, AND use λ=1.0 so MMR
        // doesn't dedupe structurally-parallel siblings.
        float floor = isEnumeration
                ? (float) cfg.minScore()
                : (float) Math.max(cfg.minScore(), top - cfg.scoreGap());

        List<Integer> eligible = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            if (scores[i] >= floor) eligible.add(i);
        }
        if (eligible.isEmpty()) {
            // No sentence cleared the floor — fall back to the single best one.
            eligible.add(indexOfBest(scores));
        }

        double lambda;
        int maxPicks;
        if (isEnumeration) {
            meterRegistry.counter(METRIC_BYPASS_ENUM).increment();
            lambda = ENUMERATION_LAMBDA;
            // Allow MMR to pick more items than the normal cap — an N-item
            // list shouldn't be truncated to (cap-1).
            int extra = cfg.maxSentences()
                    + selectionProps.enumeration().maxPicksExtra();
            int floorCap = selectionProps.enumeration().maxPicksFloor();
            maxPicks = Math.min(eligible.size(), Math.max(extra, floorCap));
        } else {
            lambda = cfg.mmrLambda();
            maxPicks = cfg.maxSentences();
        }

        return mmrSelect(sentences, scores, eligible, maxPicks, lambda);
    }

    /** Index of the highest-scoring sentence. */
    private static int indexOfBest(float[] scores) {
        int best = 0;
        for (int i = 1; i < scores.length; i++) if (scores[i] > scores[best]) best = i;
        return best;
    }

    /** Expands each pick to include ±window neighbor sentences for context. */
    private static TreeSet<Integer> expandWindows(List<Integer> picked, int window,
                                                  int sentenceCount) {
        TreeSet<Integer> windowed = new TreeSet<>();
        for (int idx : picked) {
            int lo = Math.max(0, idx - window);
            int hi = Math.min(sentenceCount - 1, idx + window);
            for (int n = lo; n <= hi; n++) windowed.add(n);
        }
        return windowed;
    }

    /** Emits kept sentences in document order, marking elided gaps. */
    private static String joinInDocumentOrder(List<String> sentences,
                                              TreeSet<Integer> windowed) {
        StringBuilder out = new StringBuilder();
        Integer prev = null;
        for (int idx : windowed) {
            if (prev != null && idx > prev + 1) out.append(GAP_MARKER);
            else if (prev != null) out.append(' ');
            out.append(sentences.get(idx));
            prev = idx;
        }
        return out.toString();
    }

    // ── Sentence-selection internals (constants, helpers) ─────────────────────

    /** Lambda value used when the list-aware MMR bypass triggers. 1.0 = pure
     *  relevance: no diversity penalty so structurally-parallel siblings all
     *  survive. Not a tunable knob — by definition of "bypass". */
    private static final double ENUMERATION_LAMBDA = 1.0;

    /** Inserted between picked sentences when at least one sentence was
     *  elided between them. Tells the consumer where context was clipped. */
    private static final String GAP_MARKER = " … ";

    /** Minimum sentence length kept by the splitter. Anything shorter is a
     *  fragment (stray section number, lone punctuation) the chunker left in. */
    private static final int MIN_SENTENCE_CHARS = 4;

    /** Minimum token length for Jaccard content-token sets. Drops short stop
     *  words ("is", "or", "the") and 1–2 letter abbreviations that would
     *  otherwise dominate the overlap. */
    private static final int MIN_CONTENT_TOKEN_CHARS = 3;

    // Metric names (Prometheus-style dot.delimited, project prefix mcp.search.)
    private static final String METRIC_INVOCATIONS         = "mcp.search.selection.invocations";
    private static final String METRIC_BYPASS_ENUM         = "mcp.search.selection.bypass.enum";
    private static final String METRIC_BYPASS_TOC          = "mcp.search.selection.bypass.toc";
    private static final String METRIC_FALLBACK_RERANKER   = "mcp.search.selection.fallback.reranker_unavailable";
    private static final String METRIC_FALLBACK_SHORT_CHUNK = "mcp.search.selection.fallback.short_chunk";

    /**
     * MMR (Carbonell & Goldstein, 1998): iteratively picks the candidate that
     * maximises λ·relevance − (1−λ)·max similarity-to-already-picked. λ=0.7
     * favours relevance while still penalising near-duplicates. Similarity is
     * a cheap Jaccard over content tokens — no extra model required.
     */
    private static List<Integer> mmrSelect(List<String> sentences, float[] scores,
                                           List<Integer> eligible, int maxPicks, double lambda) {
        int target = Math.min(maxPicks, eligible.size());
        List<Integer> picked = new ArrayList<>(target);
        List<Integer> remaining = new ArrayList<>(eligible);
        List<Set<String>> tokens = new ArrayList<>(sentences.size());
        for (String s : sentences) tokens.add(contentTokens(s));

        while (!remaining.isEmpty() && picked.size() < target) {
            int bestIdx = -1;
            double bestScore = -Double.MAX_VALUE;
            for (int idx : remaining) {
                double relevance = scores[idx];
                double maxSim = 0.0;
                for (int p : picked) {
                    double sim = jaccard(tokens.get(idx), tokens.get(p));
                    if (sim > maxSim) maxSim = sim;
                }
                double mmr = lambda * relevance - (1.0 - lambda) * maxSim;
                if (mmr > bestScore) {
                    bestScore = mmr;
                    bestIdx   = idx;
                }
            }
            picked.add(bestIdx);
            remaining.remove(Integer.valueOf(bestIdx));
        }
        return picked;
    }

    /**
     * Heuristic: does the sentence pool look like an enumeration
     * (e.g. "Instantiate VNF / Query VNF / Scale VNF / Check VNF / Heal VNF")?
     *
     * Buckets sentences by a configured token index (default = 1, the second
     * word). If any bucket holds ≥{@code minBucketCount} sentences we treat
     * the pool as a list — these are sibling items that MMR would otherwise
     * wrongly dedupe, costing the consumer the full enumeration.
     *
     * The second token works empirically because enumeration items typically
     * share a *category* word in that position: "X VNF", "X service", "X.733",
     * etc. First-token bucketing misses cases where the leading verb varies.
     */
    boolean looksLikeEnumeration(List<String> sentences) {
        var cfg = selectionProps.enumeration();
        if (sentences.size() < cfg.minBucketCount()) return false;
        Map<String, Integer> buckets = new HashMap<>();
        for (String sentence : sentences) {
            String key = bucketKey(sentence, cfg);
            if (key != null) buckets.merge(key, 1, Integer::sum);
        }
        for (int count : buckets.values()) {
            if (count >= cfg.minBucketCount()) return true;
        }
        return false;
    }

    /**
     * Bucket key for a sentence (normalised token at the configured index), or
     * {@code null} when the sentence has no usable token there.
     */
    private static String bucketKey(String sentence,
                                    SentenceSelectionProperties.Enumeration cfg) {
        String[] toks = sentence.split("\\s+");
        if (toks.length <= cfg.bucketTokenIndex()) return null;
        String key = toks[cfg.bucketTokenIndex()]
                .replaceAll("[^A-Za-z0-9]", "")
                .toLowerCase(Locale.ROOT);
        if (key.length() < cfg.minSecondTokenLength()) return null;
        return key;
    }

    /**
     * Identifies chunks whose useful content is structural (a TOC, an
     * abbreviation table, a parameter list) rather than prose. Sentence-level
     * extraction collapses these because their entries aren't sentence-shaped.
     *
     * Two heuristics, OR'd together:
     *   1. TOC dot pattern — "section title ........... 42" appears
     *      ≥{@code tocMinPatternHits} times.
     *   2. Short-line ratio — when newlines are present and there are at
     *      least {@code shapeMinLines} non-empty lines, ≥{@code shortLineRatioPct}%
     *      of those lines fall below {@code shortLineMaxChars}.
     *
     * Reference-list chunks ("[32] 3GPP TS 38.314: …") are NOT classified as
     * TABLE_OR_TOC: they're sentence-shaped, the reranker handles them, and
     * forcing them through bypass would dump useless boilerplate.
     */
    ChunkShape detectShape(String raw) {
        if (raw == null || raw.isBlank()) return ChunkShape.PROSE;
        var cfg = selectionProps.detection();

        // Heuristic 1: TOC leader-dot pattern.
        var matcher = tocDotPattern.matcher(raw);
        int hits = 0;
        while (matcher.find() && hits < cfg.tocMinPatternHits()) hits++;
        if (hits >= cfg.tocMinPatternHits()) return ChunkShape.TABLE_OR_TOC;

        // Heuristic 2: short-line dominance (only when newlines are preserved).
        String[] lines = raw.split("\\r?\\n");
        if (lines.length >= cfg.shapeMinLines() && shortLinesDominate(lines, cfg)) {
            return ChunkShape.TABLE_OR_TOC;
        }
        return ChunkShape.PROSE;
    }

    /**
     * True when there are at least {@code shapeMinLines} non-empty lines and
     * ≥{@code shortLineRatioPct}% of them fall below {@code shortLineMaxChars}.
     */
    private static boolean shortLinesDominate(String[] lines,
                                              SentenceSelectionProperties.Detection cfg) {
        int total = 0;
        int shortLines = 0;
        for (String l : lines) {
            String t = l.strip();
            if (t.isEmpty()) continue;
            total++;
            if (t.length() < cfg.shortLineMaxChars()) shortLines++;
        }
        return total >= cfg.shapeMinLines()
                && shortLines * PERCENT_SCALE >= total * cfg.shortLineRatioPct();
    }

    /** Visible for testing — the two shapes the selector distinguishes. */
    enum ChunkShape { PROSE, TABLE_OR_TOC }

    private static String capText(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + GAP_MARKER.trim();
    }

    /**
     * Sentence split using Java's BreakIterator. Handles common English
     * abbreviations (e.g., i.e., Mr., etc.) out of the box. Discards
     * fragments shorter than {@link #MIN_SENTENCE_CHARS} — typically section
     * numbers or stray punctuation left by the chunker.
     *
     * <p>Visible (package-private) for testing.
     */
    static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        BreakIterator iter = BreakIterator.getSentenceInstance(Locale.US);
        iter.setText(text);
        int start = iter.first();
        for (int end = iter.next(); end != BreakIterator.DONE; start = end, end = iter.next()) {
            String s = text.substring(start, end).strip();
            if (s.length() >= MIN_SENTENCE_CHARS) out.add(s);
        }
        return out;
    }

    private static Set<String> contentTokens(String s) {
        Set<String> out = new HashSet<>();
        for (String t : s.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (t.length() >= MIN_CONTENT_TOKEN_CHARS) out.add(t);
        }
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int inter = 0;
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger  = smaller == a ? b : a;
        for (String t : smaller) if (larger.contains(t)) inter++;
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }

    /**
     * Tunable knobs for {@link #selectRelevantSentences}. Built from
     * {@link SentenceSelectionProperties} via
     * {@link #briefSelection()} / {@link #normalSelection()}. Tests can
     * construct instances directly with arbitrary values.
     *
     * @param maxSentences  hard cap on the number of MMR picks (before window expansion)
     * @param scoreGap      adaptive band — keep sentences within this of top score
     * @param minScore      absolute floor — sentences below this are discarded outright
     * @param mmrLambda     0.0=pure diversity, 1.0=pure relevance
     * @param window        neighbor sentences (±N) kept around each pick for context
     * @param fallbackChars char cap used when the reranker isn't available
     */
    public record SelectionConfig(
            int    maxSentences,
            double scoreGap,
            double minScore,
            double mmrLambda,
            int    window,
            int    fallbackChars
    ) {
        /** Build a SelectionConfig from a property preset + global minScore. */
        public static SelectionConfig fromProps(
                SentenceSelectionProperties.Preset preset, double minScore) {
            return new SelectionConfig(
                    preset.maxSentences(),
                    preset.scoreGap(),
                    minScore,
                    preset.mmrLambda(),
                    preset.window(),
                    preset.fallbackChars()
            );
        }
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    /**
     * Splits candidates into BATCH_SIZE chunks and scores them in parallel using
     * ForkJoinPool. OrtSession.run() is thread-safe in ONNX Runtime 1.20+, so
     * multiple batches can execute concurrently on separate CPU cores.
     *
     * With RERANK_CANDIDATES=24 and BATCH_SIZE=12, this fires 2 parallel ONNX
     * inference calls, cutting reranker latency by ~40-50% on multi-core hardware.
     */
    private List<ScoredHit> scoreBatched(String query, List<SearchHit> candidates) {
        int numBatches = (candidates.size() + BATCH_SIZE - 1) / BATCH_SIZE;
        if (numBatches == 1) {
            // Single batch — no parallel overhead
            float[] scores = scoreBatch(query, candidates);
            List<ScoredHit> out = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                out.add(new ScoredHit(candidates.get(i), scores[i]));
            }
            return out;
        }

        // Multiple batches — submit all to ForkJoinPool and join
        @SuppressWarnings("unchecked")
        ForkJoinTask<float[]>[] tasks = new ForkJoinTask[numBatches];
        int[] batchStarts = new int[numBatches];
        int[] batchEnds   = new int[numBatches];
        for (int b = 0; b < numBatches; b++) {
            batchStarts[b] = b * BATCH_SIZE;
            batchEnds[b]   = Math.min(batchStarts[b] + BATCH_SIZE, candidates.size());
            List<SearchHit> batch = candidates.subList(batchStarts[b], batchEnds[b]);
            tasks[b] = ForkJoinPool.commonPool().submit(() -> scoreBatch(query, batch));
        }

        List<ScoredHit> out = new ArrayList<>(candidates.size());
        for (int b = 0; b < numBatches; b++) {
            float[]         scores = tasks[b].join();
            List<SearchHit> batch  = candidates.subList(batchStarts[b], batchEnds[b]);
            for (int i = 0; i < batch.size(); i++) {
                out.add(new ScoredHit(batch.get(i), scores[i]));
            }
        }
        return out;
    }

    private float[] scoreBatch(String query, List<SearchHit> batch) {
        List<String> passages = new ArrayList<>(batch.size());
        for (SearchHit hit : batch) {
            passages.add(hit.title() + ": " + hit.snippet());
        }
        return scoreTexts(query, passages);
    }

    /**
     * Low-level cross-encoder scoring against plain-text passages. Returns a
     * sigmoid score in [0, 1] per passage. Used by both chunk-level reranking
     * (with title-prefixed snippets) and sentence-level selection.
     *
     * On any exception the method logs and returns an all-zero array — the
     * caller decides whether to degrade gracefully or surface an error.
     */
    private float[] scoreTexts(String query, List<String> passages) {
        int     batchSize = passages.size();
        float[] results   = new float[batchSize];
        if (batchSize == 0) return results;

        try {
            Encoding[] encodings = new Encoding[batchSize];
            int seqLen = 0;
            for (int i = 0; i < batchSize; i++) {
                encodings[i] = tokenizer.encode(query, passages.get(i));
                seqLen = Math.max(seqLen, encodings[i].getIds().length);
            }

            long[] inputIds      = new long[batchSize * seqLen];
            long[] attentionMask = new long[batchSize * seqLen];
            long[] tokenTypeIds  = new long[batchSize * seqLen];

            for (int i = 0; i < batchSize; i++) {
                long[] ids   = encodings[i].getIds();
                long[] mask  = encodings[i].getAttentionMask();
                long[] types = encodings[i].getTypeIds();
                int    len   = ids.length;
                System.arraycopy(ids,  0, inputIds,      i * seqLen, len);
                System.arraycopy(mask, 0, attentionMask, i * seqLen, len);
                if (types != null && types.length == len) {
                    System.arraycopy(types, 0, tokenTypeIds, i * seqLen, len);
                }
            }

            long[] shape = {batchSize, seqLen};

            Map<String, OnnxTensor> inputs = new HashMap<>();
            try {
                inputs.put("input_ids",
                        OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(inputIds), shape));
                inputs.put("attention_mask",
                        OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(attentionMask), shape));
                if (inputNames.contains("token_type_ids")) {
                    inputs.put("token_type_ids",
                            OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(tokenTypeIds), shape));
                }

                try (OrtSession.Result result = ortSession.run(inputs)) {
                    float[][] logits = (float[][]) result.get("logits")
                            .orElseThrow(() -> new IllegalStateException("Missing 'logits' output"))
                            .getValue();
                    for (int i = 0; i < batchSize; i++) {
                        results[i] = sigmoid(logits[i][0]);
                    }
                }
            } finally {
                inputs.values().forEach(t -> {
                    try {
                        t.close();
                    } catch (Exception ex) {
                        log.trace("OnnxTensor.close() failed: {}", ex.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            log.warn("Reranker batch scoring failed, returning 0 scores: {}", e.getMessage());
        }
        return results;
    }

    private float score(String query, String passage) {
        return scoreTexts(query, List.of(passage))[0];
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }

    /**
     * Downloads {@code url} to {@code ~/.cache/3gpp-mcp/<filename>}, reusing
     * the cached file if it already exists (skips download on repeated startups).
     *
     * Reads {@code HF_TOKEN} from the environment and adds it as a Bearer
     * Authorization header when present — required for gated HuggingFace models.
     */
    private static Path cachedDownload(String url, String prefix, String suffix)
            throws IOException, InterruptedException {
        Path cacheDir = Path.of(System.getProperty("user.home"), CACHE_DIR_NAME);
        Files.createDirectories(cacheDir);
        // The cache key MUST include the URL. It used to be a fixed filename, so
        // pointing RERANKER_MODEL_URI at a different model silently reused
        // whichever model was downloaded first — the server logged the new name
        // and served the old weights. That makes any reranker A/B invalid, and
        // gives no visible symptom beyond identical scores.
        String filename = prefix + "-" + shortHash(url) + suffix;
        Path dest = cacheDir.resolve(filename);
        if (Files.exists(dest) && Files.size(dest) > 0) {
            log.info("Reranker: using cached {}", dest);
            return dest;
        }
        log.info("Reranker: downloading {} → {}", url, dest);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(DOWNLOAD_CONNECT_TIMEOUT_SECONDS))
                .build();

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(DOWNLOAD_REQUEST_TIMEOUT_MINUTES))
                .GET();

        // Honour HF_TOKEN env var for gated or private HuggingFace models
        String hfToken = System.getenv("HF_TOKEN");
        if (hfToken != null && !hfToken.isBlank()) {
            reqBuilder.header("Authorization", "Bearer " + hfToken.strip());
        }

        Path tmp = dest.resolveSibling(filename + ".tmp");
        HttpResponse<Path> response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofFile(tmp));
        if (response.statusCode() != HTTP_OK) {
            Files.deleteIfExists(tmp);
            throw new IOException("Download failed HTTP " + response.statusCode() + " for " + url
                    + (hfToken == null ? " (tip: set HF_TOKEN env var if model is gated)" : ""));
        }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        if (log.isInfoEnabled()) {
            log.info("Reranker: downloaded {} ({} MB)", filename,
                    String.format("%.1f", Files.size(dest) / 1_000_000.0));
        }
        return dest;
    }

    /** Short, stable, filesystem-safe digest of a URL for cache keying. */
    private static String shortHash(String url) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(SHORT_HASH_HEX_CHARS);
            for (int i = 0; i < SHORT_HASH_BYTES; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private record ScoredHit(SearchHit hit, float score) {}
}

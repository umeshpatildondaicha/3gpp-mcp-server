package com.vwaves.mcp.service;

import com.vwaves.mcp.model.SearchHit;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
public class ThreeGppToolService {
    private static final Logger log = LoggerFactory.getLogger(ThreeGppToolService.class);

    // ── Exact-match result cache ─────────────────────────────────────────────
    // Repeat of an IDENTICAL query with identical filters only. There is no
    // fuzzy/semantic matching here by design; do not add one without re-reading
    // why this line exists — a near-miss served from cache is a wrong answer.
    //
    // The win is the caller's iteration loop rather than any single request: a
    // config-audit payload issues one search per parameter (26 on the Juniper
    // export, 64 s of retrieval), and re-running the same payload while tuning
    // replays exactly those queries.
    private static final long CACHE_TTL_MILLIS = java.util.concurrent.TimeUnit.MINUTES.toMillis(10);
    private static final int  CACHE_MAX_ENTRIES = 500;

    private record CacheEntry(String value, long expiresAtMillis) {}

    private final Map<String, CacheEntry> searchCache = Collections.synchronizedMap(
            new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > CACHE_MAX_ENTRIES;
                }
            });

    /** Null when missing or expired; evicts an expired entry on the way out. */
    private String cachedSearchResult(String key) {
        CacheEntry e = searchCache.get(key);
        if (e == null) return null;
        if (e.expiresAtMillis() < System.currentTimeMillis()) {
            searchCache.remove(key);
            return null;
        }
        return e.value();
    }

    private final EmbeddingService embeddingService;
    private final KbDataService kbDataService;
    private final GlossaryService glossaryService;
    private final QueryLogger queryLogger;
    private final RerankService rerankService;
    private final ScopeGateService scopeGateService;
    private final IntentClassifierService intentClassifier;
    private final LexiconService lexiconService;
    private final ProcedureLayerService procedureConfig;
    private final SeriesCatalogService seriesCatalog;
    private final com.vwaves.mcp.config.RetrievalProperties props;

    public ThreeGppToolService(
            EmbeddingService embeddingService,
            KbDataService kbDataService,
            GlossaryService glossaryService,
            QueryLogger queryLogger,
            RerankService rerankService,
            ScopeGateService scopeGateService,
            IntentClassifierService intentClassifier,
            LexiconService lexiconService,
            ProcedureLayerService procedureConfig,
            SeriesCatalogService seriesCatalog,
            com.vwaves.mcp.config.RetrievalProperties props
    ) {
        this.embeddingService = embeddingService;
        this.kbDataService = kbDataService;
        this.glossaryService = glossaryService;
        this.queryLogger = queryLogger;
        this.rerankService = rerankService;
        this.scopeGateService = scopeGateService;
        this.intentClassifier = intentClassifier;
        this.lexiconService = lexiconService;
        this.procedureConfig = procedureConfig;
        this.seriesCatalog = seriesCatalog;
        this.props = props;
    }

    // Shared verbatim by search3gpp and search3gppBatch. They describe the same
    // index and must read identically to a model choosing between them, so the
    // text is declared ONCE here rather than copied — two copies would drift the
    // first time either is edited. A static final String initialised from a text
    // block is a compile-time constant, so it is legal as an annotation value.
    private static final String SEARCH_DESCRIPTION = """
            Semantic search across a curated multi-source telecom knowledge base.
            Indexed standards bodies (use this tool for any of them):
              • 3GPP TS — series 23/24/28/29/32/33/36/38 (architecture, NAS,
                OAM, core protocols, security, LTE, 5G NR)
              • ITU-T Recommendations — G-series (G.709/G.798/G.806/G.872/
                G.8032/G.8275.1) for optical transport, OTN, sync, ERPS
              • IETF RFCs — BGP-4 (4271), BFD (5880), NETCONF (6241),
                YANG (7950), Segment Routing (8402), topology models
              • ETSI NFV — NFV-MAN/SOL/IFA (orchestration, lifecycle, APIs)
              • O-RAN Alliance — WG4 fronthaul, WG10 OAM
              • GSMA, MEF, TM Forum — partial (some specs are stub-only)
              • VENDOR CONFIGURATION MODELS — Juniper Junos CLI Reference
                (series "JUNIPER"): every Junos configuration statement and
                operational command, with its [edit ...] hierarchy, syntax
                block, options and release notes. Use this for Junos parameter
                names, permitted values, hierarchy paths and CLI syntax.
                Config-audit payloads naming Junos statements
                (adjust-threshold-overflow-limit, authentication-order, mtu,
                keepalives, vrf-target, ...) ARE answerable from this index —
                query the statement name, not the whole payload.

            Filters narrow the search:
              • series  — 3GPP series number ("38" for 5G NR, "23" for arch)
                          OR "ITU-T" for every ITU-T Recommendation
                          OR "ETSI-NFV" (hyphen) for the ETSI NFV specs
                          OR "JUNIPER" for the Junos CLI Reference
              • release — 3GPP release ("Rel-18")
              • docType — "TS", "TR" (3GPP), "REC" (ITU-T), "RFC" (IETF),
                          "GS" (ETSI), "CLI" (Junos statements and commands),
                          "SPEC", "DOC", "API", "GUIDE"

            PREFER NO FILTERS — this is the single most common cause of a
            wrong "not in the corpus" answer. Every filter is an exact match and
            silently removes everything that does not carry that exact value, so
            a wrong filter returns zero rows and looks IDENTICAL to a genuine
            corpus gap. Measured examples:
              • docType="SPEC" hides all 28,301 Junos statements (they are "CLI").
              • series="38" on an MPLS/transport question hides every RFC.
              • "Mpls Tunnel Down" unfiltered returns RFC 3985 at 0.53; the same
                query with docType="CLI" returns 0.07 and looks like nothing
                exists.
            Your FIRST call should carry the query alone — no series, no docType.
            Add a filter only to narrow a result set you have already seen. If a
            filtered search returns nothing, RETRY WITHOUT THE FILTERS before
            concluding anything about coverage.

            Do not append the words "alarm", "3GPP", "RFC" or a vendor name to a
            query as a hint — they are not scoping instructions, they are just
            more terms to match, and they pull the query toward documents about
            those words instead of the thing you asked about.

            NEVER send a config path as the query. An audit tool's moHierarchy
            and the vendor's own model path routinely disagree — measured on one
            Nokia payload: grt-lookup vs grt-leaking, router/policy-options vs
            policy-options, dhcp/option vs ipv4/dhcp/option-82. Query the
            PARAMETER NAME plus at most 1-2 enclosing names ("export-grt vprn",
            not "configuration/service/vprn/grt-lookup"). Checking those 30
            parameters by exact path suggested half were missing; all 30 were
            present. The name is the reliable signal; the path only breaks ties.

            A parameter is normally a ROW in its parent's parameter table, not a
            document of its own — /configure/service/vprn/interface is one doc
            listing name, description, admin-state and the rest. A hit on the
            parent IS the hit you want; do not treat it as a near-miss and keep
            searching for a document named after the leaf.

            THE QUERY MUST BE ONE SHORT QUESTION — a phrase, not a document.
            NEVER pass a JSON payload, a config export, a log dump or a file as
            the query. Averaging a whole document into one embedding matches
            nothing: a 24 KB Bulk-CM export scored 0.15 (the noise floor) with
            every result tied, and the server rejects such input outright.
            If the user hands you a document with many items, loop: extract each
            item and issue ONE short query per item, e.g.
              "si-Periodicity SIB1NB SchedulingInfo"   not the whole export.
            For a parameter's permitted values use lookupIeDefinition instead.

            When to use this tool:
              • Any standards-grounded question (3GPP, optical, IP, NFV,
                fronthaul, OAM, performance, security)
              • Cause codes, defects, procedures, KPI definitions, alarm
                models — anything defined in a published spec

            When NOT to use this tool (use WebSearch instead):
              • Vendor alarm catalogs, and command syntax for vendors NOT in
                the indexed list above. Junos IS indexed — do not send Junos
                statements to WebSearch. If a search returns hits whose Series
                reads "Juniper Junos CLI Reference", that content is in this
                corpus: cite it. Never claim a vendor is unindexed after the
                tool has just returned that vendor's documentation.
              • Active CVEs, security advisories, outage status pages
              • Recent vendor-released documentation not in this index

            Known corpus gaps — verified 2026-07-28 against the index, not
            assumed. A gap listed here is NOT a reason to skip this tool. Search
            anyway: the neighbouring indexed specs usually define the generic
            mechanism the question turns on, and the server attaches its own
            coverage note saying which spec is missing. Supplement with WebSearch
            for the authoritative text; do not refuse without searching.
              • Pseudowire / L2VPN: RFC 3985 (PWE3 architecture), RFC 4447 (PW
                setup over LDP) and RFC 5085 (VCCV) are now INDEXED as of
                2026-07-29 — search them directly. Still absent: RFC 8077,
                RFC 6310 (PW OAM mapping). The 3GPP half of the corpus has ZERO
                chunks mentioning pseudowire, which is correct: 3GPP does not
                specify transport pseudowires.
              • RFC 3209 (RSVP-TE), RFC 4364 (BGP/MPLS VPNs), IS-IS — absent.
              Indexed IETF RFCs are: 2328 (OSPF), 2863 (IF-MIB, linkUp/linkDown
              and sub-layer state propagation), 3985 (PWE3), 4271 (BGP-4), 4447
              (PW over LDP), 4750, 5085 (VCCV), 5706, 5880 (BFD), 6241
              (NETCONF), 7575, 7950 (YANG), 8040 (RESTCONF), 8316, 8345/8346
              (topology), 8402 (SR), 8525, 8969.
            """;

    @Tool(description = SEARCH_DESCRIPTION)
    public String search3gpp(
            @ToolParam(description = "Natural language search query — ONE topic. To look up several unrelated things, use the batch tool instead; do not join them with commas, because the whole string is embedded as a single vector and a blended query matches none of its parts well.") String query,
            @ToolParam(required = false, description = "Number of results to return (1-50, default 10).") Integer topK,
            @ToolParam(required = false, description = "Filter by series, e.g. \"38\" for 5G NR, \"G\" for ITU-T G-series, \"JUNIPER\" for Junos CLI Reference. Omit unless you are sure — a wrong value returns zero results.") String series,
            @ToolParam(required = false, description = "Filter by release, e.g. \"Rel-18\"") String release,
            @ToolParam(required = false, description = "Filter by document type: \"TS\"/\"TR\" (3GPP), \"REC\" (ITU-T), \"RFC\" (IETF), \"GS\" (ETSI), \"CLI\" (Junos statements/commands). Junos is \"CLI\", never \"SPEC\". Omit unless you are sure — a wrong value returns zero results and is indistinguishable from a corpus gap.") String docType,
            @ToolParam(required = false, description = "Response detail: \"brief\" (≤3 key sentences), \"normal\" (adaptive answer-focused extraction, default), or \"full\" (raw chunk text — use only when normal seems to be missing context)") String verbosity,
            @ToolParam(required = false, description = "Max chunks from any single spec (1-5, default 1). Raise to 3 when you already know the owning spec and want the exact clause — e.g. an ASN.1 parameter definition, which otherwise loses to prose that merely mentions the parameter.") Integer maxPerSpec
    ) throws SQLException {
        return searchSingle(query, topK, series, release, docType, verbosity, maxPerSpec);
    }

    /**
     * Several independent searches in one call, answered as a JSON map.
     *
     * <p>Deliberately a SEPARATE tool rather than an optional `queries` array on
     * search3gpp. The two carry the SAME description on purpose: the host UI
     * configures which tools are exposed, so only one of the pair is loaded for a
     * given deployment, and the model must read the same capability either way.
     * Keeping them separate also lets search3gpp go back to `query` being
     * genuinely required — a single tool serving both shapes had to mark BOTH
     * inputs optional, which is a schema that permits a call with neither.
     *
     * <p>Why a batch exists at all: measured with a 3-item list on gpt-oss-20b,
     * the model spent 14 rounds re-querying the first two items, never reached
     * the third, then hit the provider's token-per-minute ceiling and returned
     * nothing. Doing the loop here makes it one round trip and covers every item
     * by construction rather than relying on the model to iterate.
     */
    @Tool(description = SEARCH_DESCRIPTION)
    public String search3gppBatch(
            @ToolParam(description = "Several independent searches in ONE call, e.g. [\"Mpls Tunnel Down\", \"Mpls Lsp Change\", \"Mpls Lsp Info Change\"]. Each string is searched separately and the results come back as a JSON object keyed by query. Prefer this over calling a search tool N times: it is one round trip instead of N, and it is the only way to cover a list of alarm or parameter names without running out of tool-calling rounds. Max 10; anything beyond that is dropped and reported.") List<String> queries,
            @ToolParam(required = false, description = "Number of results per query (1-50, default 5 — lower than a single search because N queries share one response).") Integer topK,
            @ToolParam(required = false, description = "Filter by series, e.g. \"38\" for 5G NR, \"G\" for ITU-T G-series, \"JUNIPER\" for Junos CLI Reference. Applied to EVERY query in the batch. Omit unless you are sure — a wrong value returns zero results.") String series,
            @ToolParam(required = false, description = "Filter by release, e.g. \"Rel-18\"") String release,
            @ToolParam(required = false, description = "Filter by document type: \"TS\"/\"TR\" (3GPP), \"REC\" (ITU-T), \"RFC\" (IETF), \"GS\" (ETSI), \"CLI\" (Junos statements/commands). Applied to EVERY query in the batch. Junos is \"CLI\", never \"SPEC\".") String docType,
            @ToolParam(required = false, description = "Response detail: \"brief\" (≤3 key sentences), \"normal\" (adaptive answer-focused extraction, default), or \"full\" (raw chunk text — use only when normal seems to be missing context)") String verbosity,
            @ToolParam(required = false, description = "Max chunks from any single spec (1-5, default 1). Raise to 3 when you already know the owning spec and want the exact clause — e.g. an ASN.1 parameter definition, which otherwise loses to prose that merely mentions the parameter.") Integer maxPerSpec
    ) throws SQLException {
        return runBatch(queries, topK, series, release, docType, verbosity, maxPerSpec);
    }

    private String runBatch(
            List<String> queries, Integer topK, String series, String release,
            String docType, String verbosity, Integer maxPerSpec
    ) throws SQLException {
        if (queries != null) {
            List<String> batch = new ArrayList<>();
            for (String s : queries) {
                if (s != null && !s.isBlank()) batch.add(s.trim());
            }
            if (!batch.isEmpty()) {
                int dropped = Math.max(0, batch.size() - MAX_BATCH_QUERIES);
                List<String> overflow = dropped > 0
                        ? new ArrayList<>(batch.subList(MAX_BATCH_QUERIES, batch.size()))
                        : List.of();
                if (dropped > 0) batch = batch.subList(0, MAX_BATCH_QUERIES);
                // Default lower than the single-query default: N queries share one
                // response, and the caller's context window is the real budget.
                int perQueryK = topK == null ? 5 : Math.max(1, Math.min(50, topK));

                // series/docType apply to the WHOLE batch, and a batch is usually
                // heterogeneous — that is a real hazard, but dropping the filters is
                // measurably worse. On queries=[mask, export-grt, ip-prefix-list] with
                // series="JUNIPER" the filter helps two of three and hurts one:
                //   mask            filtered Juniper unicast-ethernet 0.51 | unfiltered 3GPP 38.104   0.38
                //   ip-prefix-list  filtered Juniper prefix-list      1.27 | unfiltered nokia-state   0.49
                //   export-grt      filtered omit-no-export           0.44 | unfiltered nokia-conf    0.46  <- Nokia param
                // So the filter stays, and the caller is told what it costs instead.
                // The proper fix is a per-item filter, which the schema cannot express
                // today. Until then this note is what lets a caller notice the miss —
                // and it is exactly what a caller did unprompted: it saw export-grt
                // return BGP statements, re-ran that one query alone with no filter,
                // and got the right Nokia container.
                boolean filtered = (series != null && !series.isBlank())
                        || (docType != null && !docType.isBlank());

                // JSON, not N text blocks. Measured on a 3-query batch: text 2,763
                // tokens vs JSON 1,943 (-30%), because the per-hit
                // "More: call getSpecInfo(...)" line, the "=" separators and the
                // guidance prose repeat once per query in the text form while the
                // structure carries them for free here. It also spares the caller
                // from parsing "QUERY 1/3:" headers to tell the sections apart.
                Map<String, Object> byQuery = new java.util.LinkedHashMap<>();
                Map<String, Object> meta = new java.util.LinkedHashMap<>();
                meta.put("queries", batch.size());
                if (dropped > 0) {
                    meta.put("dropped", dropped);
                    meta.put("dropped_note", "the limit is " + MAX_BATCH_QUERIES
                            + " queries per call. The " + dropped + " entries marked "
                            + "\"not_searched\" below were NOT run — that is not a result, "
                            + "and you cannot report them as not found. Send them in "
                            + "another search3gppBatch call before answering.");
                }
                if (filtered) {
                    meta.put("filters_applied_to_every_query", true);
                    meta.put("filter_note", "series/docType were applied to ALL queries below. "
                            + "If a query's hits look unrelated to what you asked — the term does "
                            + "not appear in them — that filter is probably hiding the owning "
                            + "document. Re-run THAT ONE query alone with `query=` and no filters "
                            + "before concluding the corpus lacks it.");
                }
                byQuery.put("_meta", meta);

                // Every query the caller sent gets an entry, including the ones
                // over the cap. Dropping them silently is how "not searched"
                // becomes "not found": measured on a 36-alarm list, the model
                // sent all 36 in one call, got 20 back, and reported the other 16
                // as absent from the corpus — among them Ospf Originate Lsa
                // (0.67), Flap Bgp Neighbor (0.64) and Link Down (0.37), all of
                // which the index answers. A `_meta.dropped` count did not stop
                // that, because the model reads the per-query map, not the meta.
                // The explanation lives in _meta, once. Repeating it per entry cost
                // ~3,000 chars on a 36-query call — enough, on its own, to push the
                // reply past the caller's clip budget and shrink every real excerpt.
                for (String over : overflow) {
                    byQuery.put(over, java.util.Map.of("not_searched", true));
                }
                // The queries are independent retrieval passes, so run them on the
                // shared bounded pool instead of one after the other. The pool is
                // JVM-wide and capped at 4 on purpose: measured 2026-08-10, one JVM's
                // throughput saturates at 4 concurrent searches (16 queries: 32s
                // sequential → 21s wall, no gain at 8 workers, only latency) — the
                // pipeline is already multi-threaded inside ONNX, so more batch
                // threads would just steal cores from concurrent single-query
                // callers on the same pod. Sharing one pool across batch calls also
                // means two simultaneous batches degrade fairly instead of
                // stampeding the CPU. Results are collected back IN INPUT ORDER —
                // byQuery is a LinkedHashMap keyed by the caller's own query
                // strings, and that ordering is part of the reply contract.
                List<java.util.concurrent.Future<Object>> running = new ArrayList<>(batch.size());
                for (String one : batch) {
                    running.add(BATCH_POOL.submit(() -> {
                        try {
                            // searchSingle returns a JSON document here; re-parse it so the
                            // whole reply is one object rather than a string of strings.
                            String raw = searchSingle(one, perQueryK, series, release, docType,
                                                      verbosity, maxPerSpec, true);
                            return JSON.readValue(raw, Object.class);
                        } catch (Exception e) {
                            // One failing query must not lose the others.
                            return java.util.Map.of("error",
                                    e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    }));
                }
                for (int i = 0; i < batch.size(); i++) {
                    Object value;
                    try {
                        value = running.get(i).get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        value = java.util.Map.of("error", "Interrupted: " + e.getMessage());
                    } catch (java.util.concurrent.ExecutionException e) {
                        value = java.util.Map.of("error",
                                e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
                    }
                    byQuery.put(batch.get(i), value);
                }
                // Count the empty ones and explain them once. `meta` is the same
                // object already stored under "_meta", so mutating it here still
                // lands in the serialised reply.
                long empty = byQuery.values().stream()
                        .filter(v -> v instanceof Map<?, ?> mm
                                && Boolean.TRUE.equals(mm.get("no_match")))
                        .count();
                if (empty > 0) {
                    meta.put("no_match", empty);
                    meta.put("no_match_note", NO_MATCH_NOTE);
                }
                return writeJson(byQuery, "batch");
            }
        }
        // Null, empty, or all-blank. Say so as JSON, because a caller of the batch
        // tool is parsing JSON — an English sentence here would fail its parse and
        // read as a transport fault rather than an empty input.
        return writeJson(java.util.Map.of("error",
                "`queries` is required and must contain at least one non-empty string. "
                + "For a single topic use the single-query search tool instead."), "batch");
    }

    /** Upper bound on `queries`. Each item is a full retrieval + rerank pass.
     *  Raised from 10 because a caller with a 36-alarm list hit the cap three
     *  times and simply stopped, leaving 6 alarms never searched — three of them
     *  among the best-scoring in the set. Env-overridable. */
    private static final int MAX_BATCH_QUERIES = envInt("MCP_MAX_BATCH_QUERIES", 20);

    /** JVM-wide worker pool for batch queries — see the comment at the submit
     *  site for why the cap is 4 and why it is shared rather than per-call.
     *  Daemon threads so an in-flight batch never blocks JVM shutdown.
     *  Env-overridable for CPU-rich or CPU-starved deployments. */
    private static final java.util.concurrent.ExecutorService BATCH_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(
                    Math.max(1, envInt("MCP_BATCH_PARALLELISM", 4)),
                    runnable -> {
                        Thread t = new Thread(runnable, "batch-search");
                        t.setDaemon(true);
                        return t;
                    });

    // ── Score cut ────────────────────────────────────────────────────────────
    // topK is a QUOTA today: hybridSearch returns k rows whatever they score, so
    // a query the corpus cannot answer still comes back with k rows of noise.
    // Measured over 36 alarm names at topK=5 (180 hits): only 77 scored >= 0.15,
    // and 15 of the 36 had nothing above it at all — yet every one of those still
    // spent 5 hit-blocks. The caller's context is the scarce resource, and that
    // noise is what forced the UI clipper to squeeze real excerpts to 160 chars.
    //
    // Two cuts, because one is not enough:
    //   FLOOR — absolute. Deliberately LOW (0.10). "Mpls X C Down" returns its
    //           correct answer, RFC 3813 (MPLS-LSR MIB, where mplsXCDown lives),
    //           at 0.151; a 0.15 floor would nearly discard it. The floor is only
    //           meant to remove the 0.01-0.05 band where every result is tied at
    //           the score floor and nothing matched.
    //   REL   — relative to the top hit. This does most of the work: on
    //           "Jnx Fan Failure" the hits are [0.45, 0.03, 0.03, 0.02, 0.02] —
    //           one right answer and four unrelated 3GPP chunks.
    //
    // This is a NOISE filter, not a correctness filter, and cannot become one:
    // "Sfp Insert Trap" scores 0.44 on 3GPP "Insert Subscriber Data" (wrong)
    // while "Jnx Power Supply Failure" scores 0.29 on JUNIPER-MIB (right).
    // Set RETRIEVAL_SCORE_FLOOR=0 and RETRIEVAL_REL_CUT=0 to disable entirely.
    private static final double SCORE_FLOOR = envDouble("RETRIEVAL_SCORE_FLOOR", 0.10);
    private static final double REL_CUT = envDouble("RETRIEVAL_REL_CUT", 0.25);

    // ── Batch field set ──────────────────────────────────────────────────────
    // Applies to the JSON reply ONLY, which is produced only by search3gppBatch —
    // searchSingle(asJson=false) renders the text block and is untouched. So the
    // single-query tool keeps every field, including the intent line, where it is
    // genuinely useful because there is one question and one answer.
    //
    // Field cost measured on a 20-query alarm batch (63,766 chars):
    //   excerpt 78.0% | title 3.8% | intent 3.3% | series 2.7% | spec_id 2.2%
    //   score 0.7% | release 0.3% | n, margin, top_score, distinct_specs,
    //   retrievers_agree ~0%
    // Stripping ALL metadata saves only 10% — the excerpt IS the payload, so this
    // is not a size lever. topK and verbosity are. LEAN exists for the quality
    // reason (see the intent comment in formatHitsJson), not the byte count.
    private enum BatchFields { FULL, LEAN, MINIMAL }

    /** Why a query came back with nothing. Emitted once per batch, not per query. */
    private static final String NO_MATCH_NOTE =
            "Nothing in the index matched this query above the relevance floor. Do not "
            + "cite anything for it — say it was not found. Retrying the same wording "
            + "will not help; a different spelling of the same thing might. Alarm names "
            + "are the common case: the index stores the SNMP identifier as one token "
            + "(mplsTunnelDown), so the spaced form often misses what the joined form "
            + "finds.";

    private static final BatchFields BATCH_FIELDS = parseBatchFields();

    private static BatchFields parseBatchFields() {
        // Default FULL: nothing is dropped unless it is asked for. The measurement
        // that prompted this said field removal is not a size lever anyway —
        // stripping ALL metadata saved 10% because the excerpt is 78% of the reply.
        // So the knob exists for callers who want it; it is not worth a silent
        // default that blanks a column in someone's UI.
        String v = System.getenv("MCP_BATCH_FIELDS");
        if (v == null || v.isBlank()) return BatchFields.FULL;
        try {
            return BatchFields.valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("MCP_BATCH_FIELDS='{}' is not FULL/LEAN/MINIMAL — using FULL", v);
            return BatchFields.FULL;
        }
    }

    private static int envInt(String name, int dflt) {
        try {
            String v = System.getenv(name);
            return (v == null || v.isBlank()) ? dflt : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static double envDouble(String name, double dflt) {
        try {
            String v = System.getenv(name);
            return (v == null || v.isBlank()) ? dflt : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** Drop hits that carry no signal. Never drops the top hit on its own merit
     *  alone — if the top hit itself is under the floor, the whole list goes and
     *  the caller is told nothing matched, which is the honest answer. */
    static List<SearchHit> applyScoreCut(List<SearchHit> hits) {
        if (hits.isEmpty() || (SCORE_FLOOR <= 0 && REL_CUT <= 0)) return hits;
        double top = hits.get(0).score();
        double bar = Math.max(SCORE_FLOOR, top * REL_CUT);
        List<SearchHit> kept = new ArrayList<>(hits.size());
        for (SearchHit h : hits) {
            if (h.score() >= bar) kept.add(h);
        }
        return kept;
    }

    private String searchSingle(
            String query, Integer topK, String series, String release,
            String docType, String verbosity, Integer maxPerSpec
    ) throws SQLException {
        return searchSingle(query, topK, series, release, docType, verbosity, maxPerSpec, false);
    }

    /**
     * @param asJson render the result as a JSON object instead of the text block.
     *   Only the batch path sets this: N text blocks cost ~30% more tokens than the
     *   equivalent JSON (measured on a 3-query batch: 2,763 vs 1,943), because the
     *   per-hit "More: call getSpecInfo(...)" line, the separators and the guidance
     *   prose repeat for every query. The single-query path keeps the text block —
     *   it is what every existing caller already parses.
     */
    private String searchSingle(
            String query, Integer topK, String series, String release,
            String docType, String verbosity, Integer maxPerSpec, boolean asJson
    ) throws SQLException {
        String q = defaultValue(query, "");
        if (q.isBlank()) {
            return maybeJsonError(asJson,
                    "Query is empty. Provide a natural-language search term, e.g. \"NR initial access\".");
        }
        String docReason = looksLikeDocument(q);
        if (docReason != null) {
            return maybeJsonError(asJson, "Query rejected: " + docReason + "\n\n"
                    + "This is a search query, not a document sink. A whole payload averaged\n"
                    + "into one embedding matches nothing — measured on a 24 KB Bulk-CM export,\n"
                    + "the top score was 0.15 (the noise floor) with all results tied, and the\n"
                    + "answer that followed cited unrelated specs.\n\n"
                    + "Do this instead: parse the document yourself, then issue ONE short query\n"
                    + "per item. For a Bulk-CM export that means one call per parameter:\n"
                    + "    search3gpp(query=\"si-Periodicity SIB1NB SchedulingInfo\", series=\"36\")\n"
                    + "and, for its permitted values:\n"
                    + "    lookupIeDefinition(ieName=\"si-Periodicity\", series=\"36\")\n\n"
                    + "Skip attributes 3GPP does not define — serial numbers, antenna tilt or\n"
                    + "bearing, alarm text and severities are vendor or AISG, never in a spec.");
        }
        if (series != null && !series.isBlank() && !kbDataService.indexedSeries().contains(series)) {
            return maybeJsonError(asJson, "Series '" + series + "' is not in the indexed knowledge base. "
                    + "Indexed series: " + String.join(", ", kbDataService.indexedSeries().stream().sorted().toList())
                    + ". Use the listSeries tool to see all 3GPP series and which are indexed.");
        }
        // Scope gate — runs BEFORE retrieval. When the question is about a spec
        // this index does not hold, returning the best available near-miss is
        // worse than returning nothing: measured on this corpus, such queries
        // score HIGHER than answerable ones (median top 0.905 vs 0.780), so they
        // arrive wearing a high-confidence badge. Only a knowledge lookup catches
        // this; see ScopeGateService.
        String outOfScope = scopeGateService.outOfScopeReason(q);
        if (outOfScope != null) {
            queryLogger.logQuery(q, 0, series, release, docType, 0.0, 0L, List.of());
            return maybeJsonError(asJson, outOfScope);
        }
        // Partial coverage: search normally, but say up front that the spec that
        // actually owns the topic is missing, so the caller does not mistake
        // background material for the authoritative definition.
        String caveat = scopeGateService.coverageCaveat(q);

        int k = topK == null ? 10 : Math.max(1, Math.min(50, topK));
        Verbosity vb = resolveVerbosity(verbosity);
        Integer perSpec = maxPerSpec == null ? null : Math.max(1, Math.min(5, maxPerSpec));

        // Exact-match cache key: the raw (case-sensitive) query plus every input
        // that changes the output, joined with a control char no real query would
        // ever contain, so two different field combinations can never concatenate
        // into the same key (e.g. query="ab"+k="1" vs query="a"+k="b1").
        //
        // asJson MUST be part of the key. This method renders two different
        // documents from the same hits — the text block for a single `query`, and
        // the JSON object for each item of a `queries` batch. Keying without it
        // would serve a cached text block to a batch caller, whose JSON parse then
        // fails, or worse hand JSON to a caller expecting text. The upstream
        // version this came from had no batch path and so no such field.
        String cacheKey = String.join("\u0001",
                q, String.valueOf(k),
                series == null ? "" : series,
                release == null ? "" : release,
                docType == null ? "" : docType,
                vb.name(),
                perSpec == null ? "" : perSpec.toString(),
                asJson ? "json" : "text");
        String cached = cachedSearchResult(cacheKey);
        if (cached != null) {
            log.debug("search3gpp cache hit: \"{}\" (json={})", q, asJson);
            return cached;
        }

        // AND precision path: original query only — glossary expansion injects short noisy tokens
        // (e.g. IMS→"IP Multimedia Subsystem" adds "ip" len-2 which tops the specificity sort).
        // OR recall path: expanded query for broader coverage when AND is too narrow.
        String expandedQuery = glossaryService.expand(q);

        // Dense embedding: keep the original phrase only. Empirically (2026-05-08), feeding
        // the glossary-expanded query to BGE-M3 shifts the query vector enough to demote the
        // correct OAM spec on Q12/Q34 — the cross-encoder reranker is a more reliable place
        // to inject lexical recall than the dense path.
        String embeddingQuery = stripQuestionPrefix(q);

        // Capture the discount weight that hybridSearch will apply, so the
        // query log shows which queries got the lifted weight vs the
        // 3GPP-bias. Useful for auditing the intent-detection rules.
        double extrasWeight = kbDataService.extrasDbWeightFor(q, series);

        IntentCatalogService.Intent intent = intentClassifier.classify(q).intent();

        long t0 = System.currentTimeMillis();
        KbDataService.HybridResult result = kbDataService.hybridSearchDetailed(
                q, expandedQuery,
                embeddingService.embed(embeddingQuery),
                k, series, release, docType, perSpec);
        List<SearchHit> hits = applyScoreCut(result.hits());
        long latencyMs = System.currentTimeMillis() - t0;

        queryLogger.logQuery(q, k, series, release, docType, extrasWeight, latencyMs, hits);
        String finalResult;
        if (asJson) {
            // The JSON path stays lean on purpose. Neither the adjacent-chunk
            // previews nor the cap-drop note go in here: a `queries` batch renders
            // N of these into one response, and the batch response is already the
            // thing that has to fit a context window — the UI truncates it per
            // query. The cap-drop note is also prose telling the caller to search
            // again, which is exactly what formatHitsJson exists to leave out.
            finalResult = formatHitsJson(hits, q, vb, intent, caveat);
        } else {
            String body = formatHits(hits, q, vb, intent, caveat != null, result.capDrops());
            finalResult = caveat == null ? body : caveat + "\n\n" + body;
        }
        searchCache.put(cacheKey, new CacheEntry(finalResult, System.currentTimeMillis() + CACHE_TTL_MILLIS));
        return finalResult;
    }

    private enum Verbosity { BRIEF, NORMAL, FULL }

    private static Verbosity resolveVerbosity(String raw) {
        if (raw == null || raw.isBlank()) return Verbosity.NORMAL;
        return switch (raw.trim().toLowerCase()) {
            case "brief", "short", "terse" -> Verbosity.BRIEF;
            case "full",  "raw",   "all"   -> Verbosity.FULL;
            default                        -> Verbosity.NORMAL;
        };
    }

    @Tool(description = """
            Cross-spec evidence bundle for a 3GPP PROCEDURE (e.g. "PDU session
            establishment", "5G registration", "Xn handover", "IMS registration").

            A 3GPP procedure is never described in one spec: the flow lives in a
            stage-2 architecture spec (23.xxx), the messages in a stage-3
            signalling spec (24.xxx / 29.xxx), and the radio legs in 38.xxx /
            36.xxx. A single search3gpp call returns one ranked list in which one
            layer usually crowds out the others.

            This tool runs a separate series-scoped retrieval per layer and
            returns the union, grouped by layer and labelled by role, so the
            evidence needed to assemble an end-to-end message sequence chart is
            present in one response.

            NOTE: this returns grouped, cited EVIDENCE — it does not itself write
            the call flow. Assemble the numbered MSC from the returned clauses and
            cite the spec shown for each step. Do not introduce steps that no
            returned chunk supports.

            Use search3gpp instead for single-spec or definitional questions.
            """)
    public String getProcedureFlow(
            @ToolParam(description = "Procedure name, e.g. \"PDU session establishment\" or \"5G AKA authentication\"") String procedure,
            @ToolParam(required = false, description = "Chunks per layer (1-8, default 3)") Integer perLayer,
            @ToolParam(required = false, description = "Restrict to a technology: \"5G\" (default), \"LTE\", or \"both\"") String technology
    ) throws SQLException {
        String p = defaultValue(procedure, "");
        if (p.isBlank()) {
            return "Procedure is empty. Provide a procedure name, e.g. \"PDU session establishment\".";
        }
        Integer perOverride = perLayer == null ? null : Math.max(1, Math.min(8, perLayer));
        List<ProcedureLayerService.Layer> activeLayers =
                procedureConfig.layersFor(defaultValue(technology, "5G"));
        if (activeLayers.isEmpty()) {
            return "No procedure layers are configured. Check retrieval/procedure-layers.tsv.";
        }

        // Expand the caller's wording toward how the specs phrase it. Measured:
        // "EPS attach" put 23.401 at rank 4 (0.648); the spec's own phrasing put
        // it at rank 1 (0.991).
        String searchTerm = procedureConfig.expandProcedure(p);
        float[] vector = embeddingService.embed(stripQuestionPrefix(searchTerm));
        String expanded = glossaryService.expand(searchTerm);

        List<String> lines = new ArrayList<>();
        lines.add("Cross-spec procedure evidence for: \"" + p + "\"");
        lines.add("(one series-scoped retrieval per specification layer; "
                + "assemble the numbered flow from these clauses and cite each step)");
        lines.add("");

        // Pass 1 — retrieve every layer, then find the best score across all of
        // them. The floor has to be global: scoring 0.26 means something very
        // different when the best hit anywhere is 0.99 versus 0.35.
        Map<ProcedureLayerService.Layer, List<SearchHit>> byLayer = new java.util.LinkedHashMap<>();
        double bestOverall = 0.0;
        for (ProcedureLayerService.Layer layer : activeLayers) {
            if (!kbDataService.indexedSeries().contains(layer.series())) continue;
            int per = perOverride != null ? perOverride : layer.perLayer();

            List<SearchHit> hits = kbDataService.hybridSearch(
                    searchTerm, expanded, vector, per, layer.series(), null, null);
            // Conformance/test specs describe how to TEST a procedure, never how
            // it works — never a valid citation in a call flow.
            hits = hits.stream()
                    .filter(h -> !lexiconService.isTestSpec(h.specId()))
                    .toList();
            // Prefer the generation's own spec family within this layer. A boost,
            // not a filter: interworking procedures still surface the other side.
            hits = hits.stream()
                    .map(h -> ProcedureLayerService.isPreferred(layer, h.specId())
                            ? h.withScore(Math.min(1.0, h.score() * props.procedurePreferBoost()))
                            : h)
                    .sorted(java.util.Comparator.comparingDouble(SearchHit::score).reversed())
                    .toList();
            byLayer.put(layer, hits);
            for (SearchHit h : hits) bestOverall = Math.max(bestOverall, h.score());
        }

        double floor = Math.max(props.procedureLayerFloor(),
                bestOverall * props.procedureLayerRelativeFloor());

        int layersWithEvidence = 0;
        List<String> emptyLayers = new ArrayList<>();
        for (ProcedureLayerService.Layer layer : activeLayers) {
            List<SearchHit> hits = byLayer.get(layer);
            if (hits == null) continue;

            List<SearchHit> kept = new ArrayList<>(
                    hits.stream().filter(h -> h.score() >= floor).toList());
            if (kept.isEmpty()) {
                // Abstain rather than pad. The tool tells the caller to say a leg
                // is missing rather than invent it; that is only honest if the
                // tool itself declines to supply weak evidence as if it were real.
                emptyLayers.add(layer.label() + " (series " + layer.series() + ")");
                continue;
            }
            layersWithEvidence++;

            // Document order within a spec approximates step order for a
            // procedure clause, so a numbered flow can be read straight off this
            // rather than reassembled from score-ranked fragments.
            kept.sort(java.util.Comparator
                    .comparing(SearchHit::specId)
                    .thenComparingInt(SearchHit::chunkIndex));

            lines.add("=== " + layer.label() + " (series " + layer.series() + ") ===");
            for (SearchHit h : kept) {
                String snippet = h.snippet() == null ? "" : h.snippet().replaceAll("\\s+", " ").strip();
                String key = rerankService.selectRelevantSentences(
                        p, h.snippet() == null ? "" : h.snippet(), rerankService.normalSelection());
                lines.add("  " + h.specId() + " | " + h.release()
                        + " | chunk " + h.chunkIndex() + " | score " + h.score());
                lines.add("    " + (key.isBlank() ? snippet : key));
            }
            lines.add("");
        }

        if (!emptyLayers.isEmpty()) {
            lines.add("No strong evidence in: " + String.join("; ", emptyLayers));
            lines.add("State that these legs are unsupported rather than inferring them.");
            lines.add("");
        }

        if (layersWithEvidence == 0) {
            return "No cross-spec evidence found for procedure \"" + p + "\".\n"
                    + "Try search3gpp with the procedure name, or check the spelling.";
        }
        lines.add("Layers with evidence: " + layersWithEvidence + " of " + activeLayers.size()
                + " (evidence floor " + String.format("%.2f", floor) + ").");
        return String.join("\n", lines);
    }

    @Tool(description = """
            Look up the ASN.1 definition of a 3GPP information element and return
            its PERMITTED VALUES verbatim — the enumeration or integer range.

            Use this, not search3gpp, whenever the question is "what values is
            this parameter allowed to take" — configuration audits, compliance
            checks, validating a value against the standard.

            Why: an IE definition is an exact textual pattern
            ("si-Periodicity-r13 ENUMERATED {rf64, rf128, ...}"), not a topic.
            Semantic search ranks readable prose that MENTIONS the parameter above
            the dense ASN.1 block that DEFINES it, so search3gpp reliably returns
            a passage discussing the IE without its permitted values. Measured on
            a 30-parameter audit: search3gpp surfaced the values for 4, this for 16.

            Pass the SPEC's own IE name, not a vendor attribute name — e.g.
            "maxRetxThreshold" not "ulMaxRetxThreshold", "prach-ConfigIndex" not
            "prachIndex". If unsure of the spec name, call search3gpp first.

            Several release variants may come back (-r13, -r14, ...) with DIFFERENT
            permitted values — si-Periodicity differs between NB-IoT and MBMS. Pick
            using the managed-object context; do not merge them.

            Returns nothing when the IE is not defined in the indexed specs, which
            for a vendor or hardware attribute is the correct answer.
            """)
    public String lookupIeDefinition(
            @ToolParam(description = "The spec's own IE name, e.g. \"maxRetxThreshold\", \"prach-ConfigIndex\", \"si-Periodicity\"") String ieName,
            @ToolParam(required = false, description = "Restrict to a series, e.g. \"36\" for LTE or \"38\" for NR") String series,
            @ToolParam(required = false, description = "Max definitions to return (1-20, default 8)") Integer limit
    ) throws SQLException {
        String ie = defaultValue(ieName, "");
        if (ie.isBlank()) {
            return "ieName is required, e.g. \"maxRetxThreshold\".";
        }
        int max = limit == null ? 8 : Math.max(1, Math.min(20, limit));
        List<KbDataService.IeDefinition> defs =
                kbDataService.lookupIeDefinition(ie, series, max);
        if (defs.isEmpty()) {
            return "No ASN.1 definition found for \"" + ie + "\""
                    + (series == null || series.isBlank() ? "" : " in series " + series) + ".\n"
                    + "Either the spec uses a different name for it (try search3gpp to find the\n"
                    + "spec's own spelling), or it is a vendor/hardware attribute that 3GPP does\n"
                    + "not define. Do not infer its permitted values.";
        }
        List<String> lines = new ArrayList<>();
        lines.add("ASN.1 definitions for \"" + ie + "\" (" + defs.size() + " found)");
        lines.add("");
        for (KbDataService.IeDefinition d : defs) {
            lines.add(d.specId() + " | " + d.release() + " | " + d.ieName());
            lines.add("    " + d.definition());
            lines.add("    context: ..." + d.context());
            lines.add("");
        }
        lines.add("These are the permitted values, quoted from the spec. Compare the configured");
        lines.add("value against them directly. Where several release variants appear, choose by");
        lines.add("the managed-object context rather than merging the lists.");
        return String.join("\n", lines);
    }

    @Tool(description = """
            Check a drafted answer's spec citations against the indexed corpus
            before you present it to the user.

            Give it the original question and your draft answer. It extracts every
            3GPP spec ID cited in the draft, re-runs retrieval for the question,
            and reports which citations are backed by retrieved evidence, which
            are unsupported, and which relevant specs the draft omitted.

            Use this after composing an answer that cites specific specs,
            especially for procedure or troubleshooting answers where a wrong
            clause reference is costly.
            """)
    public String validateAnswer(
            @ToolParam(description = "The original user question") String question,
            @ToolParam(description = "Your drafted answer, including its spec citations") String draftAnswer
    ) throws SQLException {
        String q = defaultValue(question, "");
        String a = defaultValue(draftAnswer, "");
        if (q.isBlank() || a.isBlank()) {
            return "Both question and draftAnswer are required.";
        }
        Set<String> cited = extractSpecIds(a);
        if (cited.isEmpty()) {
            return "No 3GPP spec IDs found in the draft answer — nothing to validate. "
                    + "Grounded answers should cite the spec behind each claim.";
        }

        List<SearchHit> hits = kbDataService.hybridSearch(
                q, glossaryService.expand(q), embeddingService.embed(stripQuestionPrefix(q)),
                15, null, null, null);
        Set<String> retrieved = hits.stream().map(SearchHit::specId).collect(Collectors.toSet());

        List<String> supported   = cited.stream().filter(retrieved::contains).sorted().toList();
        List<String> unsupported = cited.stream().filter(s -> !retrieved.contains(s)).sorted().toList();
        List<String> omitted     = retrieved.stream().filter(s -> !cited.contains(s)).sorted().toList();

        List<String> lines = new ArrayList<>();
        lines.add("Citation check for: \"" + q + "\"");
        lines.add("");
        lines.add("Supported   (" + supported.size() + "): "
                + (supported.isEmpty() ? "none" : String.join(", ", supported)));
        lines.add("Unsupported (" + unsupported.size() + "): "
                + (unsupported.isEmpty() ? "none" : String.join(", ", unsupported)));
        if (!unsupported.isEmpty()) {
            lines.add("  → these specs are cited in the draft but did not surface in retrieval "
                    + "for this question. Either verify them with getSpecInfo, or drop the claim. "
                    + "A spec absent from the index cannot be confirmed here.");
        }
        lines.add("Retrieved but not cited (" + omitted.size() + "): "
                + (omitted.isEmpty() ? "none" : String.join(", ", omitted)));
        lines.add("");
        KbDataService.RetrievalConfidence conf = kbDataService.confidenceOf(hits);
        lines.add("Retrieval confidence for the question: " + conf.level()
                + " (margin=" + String.format("%.3f", conf.margin()) + ")");
        return String.join("\n", lines);
    }

    /** 3GPP spec IDs as written in prose: "TS 38.331", "38.331", "32.111-2". */
    private static final java.util.regex.Pattern SPEC_ID_RE =
            java.util.regex.Pattern.compile("\\b(?:TS|TR)?\\s*(\\d{2}\\.\\d{3}(?:-\\d+)?)\\b");

    static Set<String> extractSpecIds(String text) {
        Set<String> out = new java.util.LinkedHashSet<>();
        var m = SPEC_ID_RE.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Tool(description = """
            Retrieve text chunks of a specific spec by ID. Use this when the
            user has already identified the spec they want, or when search
            results pointed to a spec but the user wants more of its content.

            Examples of valid specId values:
              • 3GPP:    "38.331", "23.501", "32.111-2"
              • ITU-T:   "ITU-T-G.798", "ITU-T-G.806", "ITU-T-G.8032"
              • IETF:    "RFC4271", "RFC5880"
              • ETSI:    "NFV-MAN001", "NFV-SOL003"
              • O-RAN:   "ORAN-WG4-CUS", "ORAN-WG10-OAM"
            """)
    public String getSpecInfo(
            @ToolParam(description = "Spec ID — e.g. \"38.331\", \"ITU-T-G.798\", \"RFC5880\"") String specId,
            @ToolParam(required = false, description = "Max chunks to return (1-20, default 5)") Integer maxChunks,
            @ToolParam(required = false, description = "ALWAYS SET THIS. What you want from the spec, e.g. \"pseudowire status notification\" or \"neighbor state machine\". Without it you get the FIRST chunks of the document, which for any long spec is its cover page and table of contents — navigation, not content. With it you get the chunks that actually discuss your topic, wherever they sit in the document.") String query
    ) throws SQLException {
        int chunks = maxChunks == null ? 5 : Math.max(1, Math.min(20, maxChunks));
        String q = defaultValue(query, "");
        List<Map<String, Object>> rows =
                kbDataService.getSpecChunks(defaultValue(specId, ""), chunks, q);
        if (rows.isEmpty()) {
            return "Spec '" + specId + "' not found.";
        }
        Map<String, Object> first = rows.getFirst();
        List<String> lines = new ArrayList<>();
        lines.add("=== 3GPP " + first.get("doc_type") + " " + first.get("spec_id") + " ===");
        lines.add("Title   : " + first.get("title"));
        lines.add("Release : " + first.get("release"));
        lines.add("Series  : " + first.get("series") + " - " + first.get("series_desc"));
        lines.add("Chunks  : showing " + rows.size() + " of " + first.get("total_chunks") + " total"
                + (q.isBlank() ? "  (document order — pass query= to get the relevant chunks instead)"
                               : "  (ranked for: \"" + q + "\")"));
        lines.add("");
        for (Map<String, Object> row : rows) {
            Number idx = (Number) row.get("chunk_index");
            lines.add("--- Chunk " + (idx.longValue() + 1) + " ---");
            lines.add(String.valueOf(row.get("text")));
            lines.add("");
        }
        return String.join("\n", lines);
    }

    @Tool(description = """
            List all indexed specs across all sources (3GPP, ITU-T, IETF,
            ETSI, O-RAN, etc.), optionally filtered by series or release.
            Useful for the orchestrator to plan whether a spec exists in
            the KB before deciding to call search3gpp vs WebSearch.
            """)
    public String listSpecs(
            @ToolParam(required = false, description = "Filter by series, e.g. \"38\" or \"G\"") String series,
            @ToolParam(required = false, description = "Filter by release, e.g. \"Rel-18\"") String release
    ) throws SQLException {
        List<Map<String, Object>> specs = kbDataService.listSpecs(series, release);
        if (specs.isEmpty()) {
            return "No specs found.";
        }
        List<String> lines = new ArrayList<>();
        lines.add("Indexed specs (" + specs.size() + " total, all sources)");
        lines.add("");
        lines.add(String.format("%-14s %-5s %-10s %-8s %s", "Spec ID", "Type", "Release", "Chunks", "Series"));
        lines.add("-".repeat(70));
        for (Map<String, Object> spec : specs) {
            lines.add(String.format("%-14s %-5s %-10s %-8s %s",
                    spec.get("spec_id"), spec.get("doc_type"), spec.get("release"), spec.get("total_chunks"), spec.get("series_desc")));
        }
        return String.join("\n", lines);
    }

    @Tool(description = "3GPP series catalog with index status.")
    public String listSeries() throws SQLException {
        Set<String> indexed = kbDataService.indexedSeries();
        List<String> lines = new ArrayList<>();
        lines.add("3GPP Series Catalog");
        lines.add("");
        lines.add(String.format("%-8s %-9s %s", "Series", "Indexed", "Description"));
        lines.add("-".repeat(55));
        seriesCatalog.entries().forEach(entry -> lines.add(String.format(
                "%-8s %-9s %s",
                entry.getKey(),
                indexed.contains(entry.getKey()) ? "yes" : "no",
                entry.getValue())));
        return String.join("\n", lines);
    }

    @Tool(description = """
            Knowledge base statistics. Surfaces total chunks, unique specs,
            indexed series, and (importantly) the count of stub-only specs
            — entries with ≤2 chunks that are registered but not really
            ingested. Stubs are silently suppressed from search results
            unless the user names the spec by ID, so the orchestrator
            should treat 'substantive specs' as the meaningful coverage.
            """)
    public String kbStats() throws SQLException {
        long total  = kbDataService.totalChunks();
        long specs  = kbDataService.totalSpecs();
        long stubs  = kbDataService.stubSpecCount();
        Set<String> series = kbDataService.indexedSeries();
        String model = kbDataService.embedModelName();

        List<String> lines = new ArrayList<>();
        lines.add("Telecom Knowledge Base Statistics");
        lines.add("=".repeat(40));
        lines.add("Total chunks      : " + NumberFormat.getIntegerInstance().format(total));
        lines.add("Unique specs      : " + specs);
        lines.add("  Substantive     : " + (specs - stubs));
        lines.add("  Stub-only (≤2)  : " + stubs);
        lines.add("Series            : " + series.size() + " (" + String.join(", ", series.stream().sorted().toList()) + ")");
        lines.add("Embed model       : " + model);
        lines.add("Transport         : Streamable HTTP");
        lines.add("Server version    : 2.0.0");
        return String.join("\n", lines);
    }

    /**
     * @param partialCoverage true when the scope gate has already reported that the
     *        spec OWNING this topic is absent. It changes what a low confidence
     *        MEANS: the scores are at the floor because the true owner was never in
     *        the pool, not because the query found nothing. Without this the two
     *        signals contradict each other — the gate says "treat these as
     *        background", the confidence note says "Do not cite these" — and a model
     *        obeys the prohibition, refusing to quote the very passages that hold
     *        the generic half of the answer. Measured 2026-07-28: agent mode
     *        retrieved RFC 2328 and RFC 2863 for a pseudowire question and then
     *        declared it had found nothing.
     */
    private String formatHits(List<SearchHit> hits, String query,
                              Verbosity verbosity, IntentCatalogService.Intent intent,
                              boolean partialCoverage,
                              Map<String, KbDataService.CapDrop> capDrops) throws SQLException {
        if (hits.isEmpty()) {
            return "No results found for: \"" + query + "\"\n"
                    + "Confidence: low (margin=0.000, top=0.00, specs=0)\n"
                    + "Intent: " + intent.hint();
        }
        RerankService.SelectionConfig cfg = switch (verbosity) {
            case BRIEF  -> rerankService.briefSelection();
            case NORMAL -> rerankService.normalSelection();
            case FULL   -> null;
        };
        KbDataService.RetrievalConfidence conf = kbDataService.confidenceOf(hits);
        List<String> lines = new ArrayList<>();
        lines.add("Search results for: \"" + query + "\"");
        lines.add("(" + hits.size() + " results, verbosity=" + verbosity.name().toLowerCase() + ")");
        // Coverage/confidence signal so the orchestrator can choose between
        // trusting these hits and falling back to WebSearch, instead of guessing.
        // "top" is the rank-1 score; it is reported for context only. The level is
        // decided by "margin" — the top score alone does not predict correctness.
        lines.add("Confidence: " + conf.level()
                + " (margin=" + String.format("%.3f", conf.margin())
                + ", top=" + String.format("%.2f", conf.topScore())
                + ", specs=" + conf.distinctSpecs()
                + ", retrievers=" + conf.support() + "/2)");
        // Say plainly what this measures. It scores how cleanly [1] separates from
        // [2] WITHIN what was retrieved. It cannot tell you the owning spec is
        // indexed at all — measured, out-of-corpus questions score HIGHER here
        // (median top 0.905 vs 0.780). The scope gate handles that, not this.
        lines.add("           (ranking separation only — not evidence the owning spec is indexed)");
        lines.add("Intent: " + intent.hint());
        // Publish what the level is actually worth, measured on the 98-question
        // benchmark, so the caller can weigh result [1] instead of guessing.
        // Publish the measured accuracy each level actually buys, so the caller
        // weighs [1] on evidence rather than on the word "high".
        lines.add(partialCoverage
                ? "Note   : the low score is EXPECTED here and is explained by the coverage "
                        + "note above — the owning spec is not indexed, so nothing could score "
                        + "well. This is NOT a reason to discard these hits. Cite them for "
                        + "whatever generic mechanism they actually state, and say plainly "
                        + "which part needs the missing spec."
                : switch (conf.level()) {
            case "none"   -> "Note   : no real match — the results are tied at the score floor, "
                    + "which means the query found nothing, not that [1] is a weak answer. "
                    + "Do not cite these. Most common cause: the query is far too long "
                    + "(send one short question per parameter, not a whole document).";
            case "high"   -> "Note   : at this margin the top result is the right spec ~88% of "
                    + "the time — [1] is a reasonable single citation.";
            case "medium" -> "Note   : the margin is narrow, but both retrievers independently "
                    + "picked this spec — right ~81% of the time. Prefer [1] but skim [2]-[3].";
            default       -> "Note   : narrow margin AND the two retrievers disagree — right only "
                    + "~27% of the time. Do not cite [1] alone; read several hits, and "
                    + "consider WebSearch if none fit.";
        });
        lines.add("");
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            // Keep both: rawSnippet preserves newlines for chunk-shape detection,
            // normalized is what gets displayed when no sentence selection runs.
            String rawSnippet = h.snippet() == null ? "" : h.snippet();
            String normalized = rawSnippet.replaceAll("\\s+", " ").strip();
            String body;
            String label;
            if (normalized.isEmpty()) {
                body  = "(chunk has no extractable text — call getSpecInfo for full content)";
                label = "Excerpt";
            } else if (cfg == null) {
                body  = normalized;
                label = "Excerpt";
            } else {
                String extracted = rerankService.selectRelevantSentences(query, rawSnippet, cfg);
                body  = extracted.isBlank() ? normalized : extracted;
                label = "Key";
            }
            lines.add("[" + (i + 1) + "] " + h.specId() + " | " + h.release() + " | Score: " + h.score());
            lines.add("    Title  : " + h.title());
            lines.add("    Series : " + h.seriesDesc());
            lines.add("    " + padLabel(label) + ": " + body);
            if (cfg != null && !normalized.isEmpty()) {
                lines.add("    More   : call getSpecInfo(specId=\"" + h.specId()
                        + "\") for the full section, or re-run search with verbosity=\"full\"");
            }
            // Chunking splits mid-procedure, so the sentence that completes this
            // hit's thought may sit one chunk over — a boundary the reranker never
            // scores. A short preview either side catches that without a second call.
            KbDataService.AdjacentContext ctx = kbDataService.adjacentContext(h, ADJACENT_PREVIEW_CHARS);
            if (ctx.before() != null) lines.add("    Before : …" + ctx.before());
            if (ctx.after()  != null) lines.add("    After  : " + ctx.after() + "…");
            // The per-spec cap (default 1) optimises for spec diversity, which can
            // silently cut a second chunk from THIS spec that scored close enough to
            // matter. "Close enough" reuses confidenceHighMargin — the same gap this
            // codebase already treats as "not reliably separated" for the top-1 vs
            // top-2 decision, so a same-spec chunk within that gap of a kept hit is
            // exactly the case where discarding it silently would be wrong.
            KbDataService.CapDrop drop = capDrops == null ? null : capDrops.get(h.specId());
            if (drop != null && (h.score() - drop.droppedTopScore()) < props.confidenceHighMargin()) {
                lines.add("    Note   : this spec had " + drop.droppedCount() + " more chunk(s) scoring "
                        + "close (" + drop.droppedTopScore() + ") that the per-spec limit cut. Re-run "
                        + "with maxPerSpec=3-5 if this spec's full picture matters here.");
            }
            lines.add("");
        }
        return String.join("\n", lines);
    }

    /** Preview length for adjacentContext() — a continuity hint, not a second chunk. */
    private static final int ADJACENT_PREVIEW_CHARS = 150;


    // ── JSON rendering for batch results ─────────────────────────────────────
    // One shared mapper; ObjectMapper is thread-safe once configured.
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String maybeJsonError(boolean asJson, String message) {
        if (!asJson) return message;
        try {
            return JSON.writeValueAsString(java.util.Map.of("error", message));
        } catch (Exception e) {
            return message;
        }
    }

    /**
     * The same hits the text formatter shows, as a JSON object.
     *
     * Deliberately NOT carried over from the text block:
     *   - the "confidence" word. It is computed from the margin between hit 1 and
     *     hit 2, which measures ranking separation, not correctness. Measured over
     *     15 representative queries: "vrf-import" scores 1.26 and is labelled low,
     *     "export-grt" scores 0.46 and is labelled high; both answers are right.
     *     60% of queries come back "low".
     *   - the three-line guidance Note. It fires on that same 60%, tells the model
     *     it is "right only ~27% of the time", and is what drove a 3-item query to
     *     spend 14 rounds re-querying two items and never reach the third.
     * The underlying numbers stay, so a caller that wants to weigh a hit still can;
     * what is gone is the prose telling it to search again.
     */
    private String formatHitsJson(List<SearchHit> hits, String query,
                                  Verbosity verbosity, IntentCatalogService.Intent intent,
                                  String caveat) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (caveat != null) out.put("coverage_note", caveat);
        // Kept at FULL and LEAN — the UI reads it off this payload for its per-query
        // panel, and it was asked for explicitly. Dropped only at MINIMAL.
        //
        // Worth knowing when reading it on a batch: it is classified from the query
        // words alone, so on vendor alarm names it is often wrong. "Jnx Fan Failure",
        // a Juniper chassis trap, is labelled "procedure — spans stage-2 flow and
        // stage-3 protocol specs; consider getProcedureFlow" — 3GPP machinery that
        // cannot answer it. Treat it as a hint about the QUERY, not about the hits.
        if (BATCH_FIELDS != BatchFields.MINIMAL) out.put("intent", intent.hint());
        if (hits.isEmpty()) {
            // Explicit, because the alternative the caller used to get was five
            // rows scoring 0.01-0.05 that it then cited as if they were answers.
            // "Nothing matched" is a usable result; five unrelated chunks are not.
            out.put("no_match", true);
            out.put("hits", List.of());
            // The explanation goes in _meta once, always — this is duplicate removal,
            // not field removal, so it is not tied to BATCH_FIELDS. Verbatim per
            // entry it was 205 chars each: 820 on a 20-query batch, ~2,460 on the
            // 36-alarm one, the same sentence every time. Same mistake the
            // not_searched marker made before it was moved.
            return writeJson(out, query);
        }
        KbDataService.RetrievalConfidence conf = kbDataService.confidenceOf(hits);
        out.put("top_score", round3(conf.topScore()));
        // These three cost about nothing (~0% of the reply, measured) and the UI
        // reads them straight off this payload for its per-query panel, so they
        // stay unless the caller explicitly asks for MINIMAL.
        if (BATCH_FIELDS != BatchFields.MINIMAL) {
            out.put("margin", round3(conf.margin()));
            out.put("distinct_specs", conf.distinctSpecs());
            out.put("retrievers_agree", conf.support() + "/2");
        }

        RerankService.SelectionConfig cfg = switch (verbosity) {
            case BRIEF  -> rerankService.briefSelection();
            case NORMAL -> rerankService.normalSelection();
            case FULL   -> null;
        };
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            String raw = h.snippet() == null ? "" : h.snippet();
            String normalized = raw.replaceAll("\\s+", " ").strip();
            String body;
            if (normalized.isEmpty()) {
                body = "(chunk has no extractable text — call getSpecInfo for full content)";
            } else if (cfg == null) {
                body = normalized;
            } else {
                String extracted = rerankService.selectRelevantSentences(query, raw, cfg);
                body = extracted.isBlank() ? normalized : extracted;
            }
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            if (BATCH_FIELDS != BatchFields.MINIMAL) row.put("n", i + 1);
            row.put("spec_id", h.specId());
            row.put("score", h.score());
            row.put("title", h.title());
            // `series` is the human-readable source name ("Juniper Junos CLI
            // Reference"). For a model it repeats what spec_id and title already
            // say; 1,733 chars / 2.7% on the 20-query batch. The UI's Evidence
            // panel does show it, so dropping it blanks that column — which is why
            // it goes at LEAN and not at FULL.
            if (BATCH_FIELDS == BatchFields.FULL) row.put("series", h.seriesDesc());
            if (BATCH_FIELDS != BatchFields.MINIMAL
                    && h.release() != null && !h.release().isBlank()) {
                row.put("release", h.release());
            }
            row.put("excerpt", body);
            rows.add(row);
        }
        out.put("hits", rows);
        return writeJson(out, query);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String writeJson(Object o, String query) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"error\":\"could not serialise results for query " + query + "\"}";
        }
    }

    private static String padLabel(String label) {
        // Keep alignment with "Title  ", "Series ", "More   " (7 chars).
        return label.length() >= 7 ? label : label + " ".repeat(7 - label.length());
    }

    /** Longest sensible question. Real queries are a phrase; anything near this
     *  is a pasted artefact. The 100-question benchmark's longest is under 60. */
    private static final int MAX_QUERY_CHARS = 600;

    /**
     * Detect a document pasted where a question belongs; returns the reason, or
     * null when the input is a normal query.
     *
     * <p>Needed because the failure is silent: the retriever returns eight
     * plausible-looking specs at the score floor, and a downstream model then
     * writes a confident answer citing them. Refusing with an explanation turns
     * an invisible wrong answer into an actionable error, and steers agentic
     * clients whose prompt we do not control.
     */
    static String looksLikeDocument(String q) {
        String t = q.strip();
        // Size and structure only — never the opening character.
        //
        // This used to reject anything starting '{' and ending '}', or '<' … '>'.
        // That is a test of TRANSPORT, not of content: MCP clients routinely wrap a
        // perfectly good question in JSON, and
        //     {"query": "VPN Pseudowire Down leading to Link Down", "max_results": 5}
        // was refused outright — 68 characters carrying one short question. The
        // refusal is meant to catch a 24 KB payload averaged into one embedding,
        // and length plus line count already catch that. A small JSON envelope is
        // cheap to search and its punctuation is stripped by extractFtsTerms
        // anyway, so the cost of letting it through is a couple of noise tokens;
        // the cost of refusing it was the whole question.
        if (t.length() > MAX_QUERY_CHARS) {
            return "the query is " + t.length() + " characters; a search query should be a "
                    + "short phrase (limit " + MAX_QUERY_CHARS + ")";
        }
        if (t.chars().filter(c -> c == '\n').count() > 8) {
            return "the query spans many lines; it looks like pasted content rather than a question";
        }
        return null;
    }

    private static String stripQuestionPrefix(String query) {
        if (query == null) return "";
        return query.replaceAll(
            "(?i)^(how\\s+(to|do|does|can|should|would)|what\\s+(is|are|does)|why\\s+(is|are)|" +
            "explain\\s+|describe\\s+|tell\\s+me\\s+(about|how)|find\\s+|show\\s+me\\s+|give\\s+me\\s+)\\s*",
            "").trim();
    }

    private String defaultValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}

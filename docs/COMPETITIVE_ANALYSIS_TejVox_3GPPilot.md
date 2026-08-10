# Competitive Analysis — TejVox "3GPPilot" vs. our 3GPP MCP Server

**Subject:** Venkata Narayana (Telecom & AI Solutions Consultant, 15+ yrs telecom), building
**3GPPilot** under **TejVox** — a grounded RAG copilot for telecom engineers.
**Source:** his public LinkedIn activity (10 posts + embedded product screenshots + comments), read 2026-07-27.
**Caveat:** this is inferred from *marketing posts and product UI screenshots*, not his code or
any published benchmark. He repeatedly says "still testing / still improving." Treat as
directional competitive intel, not verified fact.

---

## 1. What his system looks like (as revealed)

He ships several grounded-RAG assistants (3GPPilot for telecom, Contract Intelligence, Gita
Guide, EduAssist). Two product screenshots expose his actual pipeline.

**Generic RAG pipeline (Contract / Gita UI):**
```
Query → Scope & Routing → Hybrid Retrieval → RRF Fusion → Filter
      → Cross-Encoder Rerank → Confidence Gate → Grounded Answer
```

**3GPP-specific pipeline (3GPPilot UI, more agentic):**
```
User Context → Intent Routing → Aggregated Retrieval → Specialized Selector
             → Self-Validation → Adaptation
```

**Corpus scale he advertises:** 18,604+ NR (38.xxx) clauses, 7,907+ LTE clauses,
13,562+ Core (23/24/29) clauses indexed; "100% procedure coverage (core procedures)."
Note the unit is **clauses**, i.e. clause-level indexing granularity.

**Headline features:** clause-cited answers; **Generate Procedure Flow** (numbered, cited
Message Sequence Charts); **multi-spec retrieval / cross-spec reasoning**; release-aware
answers; failure-path & root-cause analysis; procedure comparison tables; confidence &
coverage scoring; transparent RAG pipeline shown to the user.

**Concrete demo he posted:** "PDU Session Establishment call flow in 5G SA" synthesized across
TS 23.502, 24.501, 38.413, 38.331, 29.244, 29.502/29.512 → end-to-end MSC with clause refs,
optional/failure branches, and troubleshooting tips. Also a UPF-restart/PFCP fault analysis
citing TS 29.244 §4.2/§6.2.6/§6.3.2 with Key Findings / Likely Causes / Recommended Actions.

**Robustness tricks he described (Contract Intelligence post):**
- **Vector rescue** — if the reranker scores a passage low but vector similarity is high, don't
  auto-reject; send it through extra validation before refusing.
- **Short-query enrichment** — expand bare keywords ("pricing" → prices/rates/charges/fees),
  applied *only* to short queries so detailed questions aren't rewritten.
- **Context-aware confidence gating** — stricter thresholds for a large multi-doc KB, looser for
  single-doc, to prevent cross-document contamination.

---

## 2. Stage-by-stage comparison

| Stage | His 3GPPilot (inferred) | Our MCP server (actual) | Verdict |
|---|---|---|---|
| Query prep | "Scope & Routing" + "Intent Routing" as first-class stages | Glossary expand (BM25 path), question-prefix strip (dense path), extras-DB weight decision, series validation | **He's ahead conceptually** — we don't classify query intent |
| Dense retrieval | Hybrid (semantic) | BGE-M3 1024-d, in-RAM cosine, per-spec cap | Parity |
| Lexical retrieval | Hybrid (keyword) | FTS5 BM25 with AND→AND-1→OR fallback, term-specificity sort, canonical-form subst | **We're ahead** (more engineered) |
| Fusion | "RRF Fusion" | RRF k=60 + co-occurrence boost + TR/extras discounts + alias-pin boost | **We're ahead** |
| Filter/diversity | "Filter" | per-spec cap, stub suppression, binary-text guard | **We're ahead** |
| Rerank | Cross-Encoder Rerank | mxbai/bge cross-encoder, re-applies discounts | Parity |
| Answer shaping | "Specialized Selector" + LLM answer | Sentence-level MMR extraction, shape detection (TOC/table bypass), verbosity presets | **We're ahead** (finer-grained) |
| Confidence | "Confidence Gate" + vector rescue | hard `min-result-score=0.15` floor only | ~~**He's ahead**~~ → **superseded**, see below |
| Validation | "Self-Validation" of the answer vs evidence | none in server (left to external LLM) | **He's ahead** |
| Cross-spec synthesis | "Generate Procedure Flow" / MSC across specs | none — returns chunks; synthesis left to LLM client | **He's ahead (biggest gap)** |
| Eval rigor | none published | benchmark_oam / verbosity / latency / non-compliance suites w/ saved baselines | **We're clearly ahead** |
| Deployment | SaaS web app | deterministic MCP server, GPU-free, k8s-ready, model-parity fail-fast | **We're ahead on ops** |

> **Superseded — Confidence row.** This table is the *pre-implementation* snapshot
> (2026-07-27 15:58). The confidence gap was closed the same day: a margin-calibrated
> gate shipped (`KbDataService.confidenceOf`, measured **high 88% / low 61%** TOP-1),
> and vector rescue was implemented, measured at **−4 TOP-1**, and removed on the
> evidence. Do not cite this row as current — see
> `TEJVOX_GAP_IMPLEMENTATION_RESULTS.md` §3.1–3.2 and the corrected scorecard in
> `HEADTOHEAD_3GPPilot_vs_ours.md`.

---

## 3. Ideas worth borrowing (prioritized, mapped to our code)

1. **Cross-spec procedure / MSC synthesis (highest value).** His headline differentiator. Our
   server returns the right chunks and leaves assembly to the LLM. Options: (a) build a
   spec cross-reference graph (which spec references which, per procedure) and add a
   `getProcedureFlow(procedure)` tool that aggregates clauses across specs; or (b) strengthen
   the `@Tool` descriptions so the orchestrator reliably chains `search3gpp` calls across the
   right specs. Product-level gap, not a retrieval-quality gap.

2. **Explicit intent routing.** Add a lightweight classifier stage before retrieval:
   procedure-explanation vs. clause-lookup vs. troubleshooting vs. out-of-scope. Route to the
   right params/tool. Today this logic is implicit (extras-weight + persona rules).

3. **Confidence gate + vector rescue.** Our pipeline has a hard `min-result-score` floor and the
   reranker score fully *replaces* RRF — a chunk the reranker underscores but the vector loved
   gets dropped. Add: keep a candidate when dense cosine is high even if rerank is low, gated by
   a secondary check. Cheap, benchmark-testable robustness win in `KbDataService.hybridSearch` /
   `RerankService.rerank`.

4. **Coverage / confidence score in the response.** Emit a numeric coverage+confidence signal in
   `formatHits()` so the orchestrator can decide `search3gpp` vs. WebSearch instead of guessing.

5. **Release-aware ranking.** We have a `release` column + filter, but RRF doesn't reward
   release consistency. Add a small boost when a hit matches the query's target release, and
   penalize mixing releases within one procedure answer.

6. **Clause-level granularity.** He indexes at *clause* level (18k+ NR clauses); we use 400-word
   sliding windows. Clause-aware chunking (split on "x.y.z" clause headings, keep clause id in
   metadata) would sharpen citations and make "cite clause §6.2.6" answers exact. Larger change —
   touches the Python ingestion (`embed_core.rechunk_spec`) and the schema.

7. **Self-validation / evidence check.** Optional server tool that checks a drafted answer's
   citations actually appear in retrieved chunks — reduces hallucinated clause numbers.

---

## 4. Where we are already ahead — keep these

- **Answer-focused sentence extraction** (MMR + adaptive floor + TOC/table shape detection +
  enumeration handling). Nothing in his posts shows this granularity; he returns whole passages.
- **Retrieval engineering depth**: term-specificity BM25 construction, AND→AND-1→OR fallback,
  canonical-form substitution, co-occurrence boost, TR study-report discount, extras-DB
  weighting, alias-pin boost, stub suppression, binary-text guard.
- **Evaluation rigor**: a real benchmark harness with saved A/B baselines. He has none published.
- **Clean architecture**: decoupled MCP server, GPU-free, deterministic, startup model/dim
  parity fail-fast, k8s manifests. His is a closed SaaS.

---

## 5. Posts & reactions — what the audience added

His 10 recent posts are product-launch/thought-leadership for 3GPPilot, Contract Intelligence,
Gita Guide, EduAssist. Engagement is real but the **comments contain almost no RAG-architecture
suggestions** — they are mostly congratulatory or vendor tagging. The one substantive technical
comment (Ike Alisson, Linux Foundation Edge Akraino TSC) is **telecom-content** feedback: on the
2G-era distinction between "System Specifications" vs "Network Topology" and "Cellular System"
vs "Cellular Network," urging precise grounding from the base specs. That's a useful reminder
about **metadata/content correctness** — and it lines up with our own known limitation of
skeletal spec titles (~1032 specs titled just by ID). No commenter proposed a retrieval technique
we don't already have.

---

## 6. Bottom line

Nothing he shows beats our **retrieval engine** — we're at parity or ahead on the pipeline and
clearly ahead on evaluation and ops. His lead is at the **capability/product layer**: cross-spec
procedure (MSC) synthesis, an explicit intent-routing + confidence-gate + self-validation flow,
release-awareness, and clause-level granularity. The highest-ROI moves for us, in order:
**(1) cross-spec procedure synthesis, (2) confidence gate + vector rescue, (3) coverage scoring,
(4) release-aware ranking** — all of which we can add without disturbing the core stack, and all
of which our existing benchmark harness can validate.

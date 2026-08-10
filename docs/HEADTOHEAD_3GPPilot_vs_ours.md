# Head-to-head — TejVox 3GPPilot vs. our 3GPP MCP server

> **Current state: 2026-07-28.** Read this section first; the rest of the file is
> the 2026-07-27 snapshot and its corrections, kept for the reasoning trail.
>
> **The comparison is still asymmetric and always has been.** 3GPPilot publishes no
> code, no architecture and no numbers. `tejvox.com/bots/pilot` is a client-rendered
> SPA that serves only "TejVox — an AI product studio" to any fetcher, and LinkedIn
> refuses unauthenticated reads (HTTP 999). Every "theirs" cell below is advertised
> copy. Every "ours" cell is read from the running server or a saved benchmark.
>
> ## Ours, verified against the live server (PID on :3000, 2026-07-28)
>
> **Benchmark — `bench/results/after_ie_lookup.json`: TOP-1 72/100, TOP-5 97/100.**
>
> | Topic | TOP-1 | | Kind | TOP-1 |
> |---|---|---|---|---|
> | PM | 19/21 | | lookup | 51/69 |
> | 5GC | 9/12 | | crossspec | **8/8** |
> | CM | 9/14 | | procedure | **13/23** |
> | FM | 7/10 | | | |
> | NR | **6/12** | | | |
> | LTE | **4/8** | | | |
>
> **Eight MCP tools:** `search3gpp`, `lookupIeDefinition`, `getProcedureFlow`,
> `validateAnswer`, `getSpecInfo`, `listSpecs`, `listSeries`, `kbStats`.
>
> **The confidence gate is no longer binary and no longer just a margin.** It now
> combines margin with cross-retriever agreement, and the value is in the negative
> conjunction (`KbDataService.confidenceOf`, measured on 98 questions):
>
> | Condition | TOP-1 accuracy | n |
> |---|---|---|
> | `margin ≥ 0.12` → **high** | 88% | 42 |
> | `margin < 0.12` but both retrievers agree → **medium** | 81% | 34 |
> | `margin < 0.12` AND agreement < 2/2 → **low** | **27%** | 22 |
> | `top < 0.25` AND `margin < 0.02` → **none** | degenerate-input guard | 1/100 |
>
> A weak margin alone was only a 51% warning — barely actionable. A weak margin
> *and* no independent agreement is a 27% warning, which is. The earlier binary gate
> collapsed the middle band into "low" and understated 34 good results.
>
> ## Updated dimension table
>
> | Dimension | 3GPPilot (advertised) | Ours (measured) | Lead |
> |---|---|---|---|
> | Retrieval quality | none published | TOP-1 72/100, TOP-5 97/100 | **Ours — only side with evidence** |
> | Confidence gate | "Confidence Gate", mechanism unpublished | 4-tier margin × agreement, each tier's accuracy measured | **Ours** — and neither TelcoAI nor DeepSpecs has one at all |
> | Vector rescue | advertised | implemented, measured −4 TOP-1, removed | **Neither — does not help this corpus** |
> | **IE permitted values** | not advertised | **`lookupIeDefinition`** returns ASN.1 enums/ranges verbatim, refuses to infer | **Ours** — not present in any system surveyed |
> | **Vendor CM attribute mapping** | not advertised | 31-entry alias layer, vendor MO names → 3GPP IE spellings | **Ours** — absent from all published systems |
> | Cross-spec synthesis | "Generate Procedure Flow" | `getProcedureFlow`, per-layer retrieval; **crossspec 8/8** | Parity |
> | Intent routing | first-class stage | `IntentClassifierService` + catalogue + scope gate | Parity |
> | Self-validation | "Self-Validation" | `validateAnswer` | Parity |
> | Observability | none published | split app/query/wire logs, saved A/B baselines, `bench/ask.py` | **Ours** |
> | **Clause-level granularity** | 18,604 NR / 7,907 LTE / 13,562 Core clauses | 400-word sliding windows | **Theirs — and it is unanimous, see below** |
>
> ## The one gap that survives every round
>
> Clause-level chunking. It is **4 of 4** across every system we can actually read
> or that publishes a claim — 3GPPilot (advertised), TelcoAI (section-based with
> parent expansion), DeepSpecs (atomic clauses). We are the only one on sliding
> windows, and the symptom is unchanged and specific: `procedure` questions score
> **13/23 TOP-1 but 23/23 TOP-5**. The right spec is retrieved every single time and
> simply is not ranked first — which is what blurred clause boundaries produce.
>
> ## Open, and cheap
>
> `lookupIeDefinition` does not consult `and-term-subst.tsv`. `si-Periodicity`
> returns `ENUMERATED {rf64 … rf4096}`; the vendor spelling `siPeriodicity` returns
> "no definition found". The alias table already holds that mapping for the BM25
> path — wiring it into the IE lookup would let a bulk-CM audit resolve permitted
> values directly from the vendor's own attribute names.

---


Merges two things we produced separately:
1. the **capability/architecture** competitive analysis of 3GPPilot (from Venkata's
   public LinkedIn posts + product screenshots, 2026-07-27), and
2. our **measured retrieval benchmark** (baseline → final "all fixes" run, scored
   by `bench/compare.py` on 98 questions against one corrected gold set).

The point of merging them: our benchmark now **quantifies** exactly where his
advertised strengths line up with our real weak spots.

> **Fairness caveat.** TejVox publishes **no benchmark numbers** — only advertised
> corpus scale and feature screenshots, and he repeatedly says "still testing."
> So this is not a same-yardstick score race. We have *measured* retrieval
> quality; he has *advertised* capabilities. Where a cell below has a number, it's
> ours and it's real; where it doesn't, no measured figure exists for either side.

---

## TL;DR

- **On the retrieval engine we can measure, we lead** — TOP-1 **67/98 (68.4%)**,
  TOP-5 **94/98 (95.9%)** on the broad 98-question set (and 37/50 = 74% TOP-1,
  49/50 = 98% TOP-5 on the focused OAM suite). He has published nothing to compare.
  Architecturally we are at parity-or-ahead on every retrieval stage.
- **His lead is entirely at the capability/product layer**: cross-spec procedure
  (MSC) synthesis, explicit intent routing, a confidence gate + vector rescue,
  self-validation, release-awareness, and clause-level indexing.
- **The useful new finding**: our benchmark shows his headline differentiator —
  cross-spec — is a *presentation* gap for us, not a retrieval gap. We already
  retrieve the right specs on **8/9** cross-spec questions; we just don't assemble
  the MSC. Meanwhile his **release-awareness** strength maps onto our single worst
  measured topic (**LTE 1/8**), making that the highest-ROI place to close.

---

## Scorecard by dimension (capability + our measured evidence)

| Dimension | 3GPPilot (advertised) | Ours (measured / actual) | Lead |
|---|---|---|---|
| **Retrieval quality (broad)** | none published | **TOP-1 67/98 (68.4%), TOP-5 94/98 (95.9%)** | **Ours (only side with evidence)** |
| **Retrieval quality (OAM suite)** | none published | **TOP-1 37/50 (74%), TOP-5 49/50 (98%)** | **Ours** |
| Dense retrieval | "hybrid (semantic)" | BGE-M3 1024-d, in-RAM cosine, per-spec cap | Parity |
| Lexical retrieval | "hybrid (keyword)" | FTS5 BM25, AND→AND-1→OR fallback, term-specificity sort | **Ours** |
| Fusion | "RRF Fusion" | RRF k=60 + co-occurrence + TR/extras discounts + alias-pin | **Ours** |
| Filter / diversity | "Filter" | per-spec cap, stub suppression, binary-text guard | **Ours** |
| Rerank | cross-encoder | mxbai/bge cross-encoder, re-applies discounts | Parity |
| Answer shaping | "Specialized Selector" | sentence-level MMR, TOC/table shape detection, verbosity presets | **Ours** |
| **Intent routing** | first-class "Intent Routing" stage | implicit (extras-weight + persona rules) | **His** |
| **Confidence gate** | "Confidence Gate" (threshold, design not published) | margin-calibrated gate + retriever-agreement signal — **high 36/41 = 88%, low 36/59 = 61%** TOP-1 | **Ours on calibration; his on enforcement** (ours is advisory — see below) |
| **Vector rescue** | advertised | implemented, measured **−4 TOP-1**, removed on evidence | **Neither — the mechanism does not help this corpus** |
| **Self-validation** | "Self-Validation" of answer vs evidence | none (left to external LLM) | **His** |
| **Cross-spec MSC synthesis** | "Generate Procedure Flow" across specs | we retrieve **8/9** cross-spec correctly, but return chunks — no MSC assembly | **His (presentation only — see below)** |
| **Release-aware ranking** | release-aware answers | `release` column exists, RRF doesn't reward it → **LTE 1/8** | **His (and it's our worst topic)** |
| **Clause-level granularity** | 18,604 NR / 7,907 LTE / 13,562 Core **clauses** | 400-word sliding windows | **His** |
| Eval rigor | none published | benchmark + verbosity + latency + saved A/B baselines | **Ours (clearly)** |
| Deployment / ops | closed SaaS | deterministic MCP, GPU-free, k8s-ready, model-parity fail-fast | **Ours** |

---

## Cross-referencing his leads against our *measured* weak spots

This is what the merge buys us — each of his capability advantages, checked against
where our numbers actually sag:

| His advantage | Our measured symptom | Reading |
|---|---|---|
| **Release-aware ranking** | **LTE 1/8 TOP-1.** NR 38-series chunks outrank LTE 36-series on LTE queries (e.g. "LTE MAC random access" → `38.321` #1, `36.321` at #2). 5 of 8 LTE golds sit in top-5 already. | **Highest ROI.** Losing on *rank*, not recall. A release/tech-aware re-rank (LTE/EPS/E-UTRAN or 36-series intent → down-weight 38-series) should take LTE from 1/8 toward 5–6/8 ≈ **+4–5 TOP-1 overall**. His exact strength, our exact hole. |
| **Clause-level granularity** | **procedure 11/22 TOP-1** — weakest *kind*. Procedures span clauses across specs; window chunks blur clause boundaries. | Real but larger change (touches ingestion + schema). Clause-aware chunking sharpens procedure citations. Medium-term. |
| **Cross-spec MSC synthesis** | **crossspec 8/9 TOP-1 — already strong.** We *find* the right specs; we just don't draw the MSC. | Reframes his "biggest gap." For us it's a **product/presentation** gap (add a `getProcedureFlow` tool or chain `search3gpp`), **not** a retrieval-quality gap. Our own numbers prove the retrieval is there. |
| **Confidence gate + vector rescue** | **gate shipped and calibrated** (`confidenceOf`, margin ≥ 0.12 → high 88% vs low 61%); rescue implemented, measured −4 TOP-1 (FM 6→3), and **removed** | **Resolved, not open.** Gating on absolute score — the obvious reading of his feature — is the thing our data says does not work; margin is the discriminating signal (correct 0.121 vs wrong 0.050). Rescue has nothing to rescue here: TOP-5 is 95/98, so the reranker is not discarding correct specs. **The one part still open is enforcement: our gate reports a level, it does not act on it.** |
| **Intent routing / self-validation** | not directly measured | Robustness/UX wins; add after the ranking fix, validate on the same harness. |

---

## Checked against the published literature (2026-07-27, later addition)

3GPPilot itself stayed unreadable — `tejvox.com/bots/pilot` is a client-rendered SPA
that serves only "TejVox — an AI product studio" to a fetcher, and LinkedIn returns
HTTP 999 without a logged-in session. So instead of re-reading his marketing, the
same questions were put to the **published** 3GPP-RAG systems, which have both
architecture detail and numbers.

| System | Confidence gate / abstention | Chunking | Reported result |
|---|---|---|---|
| **TelcoAI** (arXiv 2601.16984) | **none described** | section-based, depth-first through the heading hierarchy; retrieve sub-section then **expand to parent** | **93%** on TSpec-LLM (100 MCQ); Recall 0.87 vs Chat3GPP 0.75 |
| **DeepSpecs** (arXiv 2511.01305) | **none** — "does not describe any confidence gating, abstention, verification or self-check" | **atomic clauses** (e.g. `7.4.1.1.2 Mapping to physical resource`) | **95.4%** win rate vs GPT-4o on 573 real-world QA |
| **Chat3GPP** (arXiv 2501.13954) | not established (abstract only; PDF did not extract) | "chunking strategies, hybrid retrieval" — unspecified | "superior performance", no figures in abstract |
| **3GPPilot** (advertised) | "Confidence Gate" — mechanism never published | **clause-level**: 18,604 NR / 7,907 LTE / 13,562 Core | none published |
| **Ours** | margin-calibrated, **88% / 61%** | 400-word sliding windows | 70/98 TOP-1, 95/98 TOP-5 |

Two conclusions, and they point in opposite directions from the original scorecard:

1. **The confidence gate is not a gap — it may be a lead.** Neither of the two
   strongest published systems has one at all. We have a calibrated one with a
   measured accuracy split. Nothing in the literature supports the idea that we are
   behind here, and the one system claiming it (3GPPilot) has published no mechanism
   and no numbers. *Method note: a first pass over the DeepSpecs PDF reported
   "confidence-based abstention"; the paper's HTML says the opposite. The table
   above follows the HTML.*
2. **Clause-level chunking is unanimous — 4 of 4 — and we are the only one on
   sliding windows.** TelcoAI's parent-expansion and DeepSpecs' atomic-clause
   segmentation are the same idea from two directions. This lines up exactly with our
   worst measured symptom: `procedure` questions score **13/23 TOP-1 but 23/23
   TOP-5** — the right spec is always retrieved and simply mis-ranked, which is what
   blurred clause boundaries produce.

**TSpec-LLM (100 MCQs) is a shared yardstick** — TelcoAI reports 93% on it. Running
our retriever against it would give the first number on someone else's eval set
rather than only our own gold.

---

## What our benchmark says the priority order should be

Reordered from the original competitive analysis now that we have numbers — the
measured LTE hole promotes release-aware ranking to #1:

1. **Release/technology-aware ranking** — biggest *measured* lift (~+4–5 TOP-1),
   directly closes his release-awareness advantage, fixes our worst topic (LTE).
2. **Owning-spec tie-break** — recovers the 2 regressions from the last round
   (AMF-N2, NSSI) where a non-gold spec flipped to #1; gold already at rank 2.
3. **Cross-spec procedure-flow tool** — his headline feature, but for us it's
   packaging (retrieval already 8/9), so it's high *product* value at low
   retrieval risk.
4. ~~**Confidence gate + vector rescue**~~ — **closed 2026-07-27.** The gate shipped
   and is calibrated (88% vs 61%); vector rescue was measured harmful (−4) and
   removed, with the numbers recorded in `application.properties` so it is not
   re-enabled blind. What remains is not calibration but **enforcement**: the gate
   emits a level and nothing consumes it. Making `low` trigger a second retrieval
   pass (keep whichever pass yields the higher margin) is the benchmark-testable
   next step — but it ranks below items 1–3, which move TOP-1 directly.
5. **Clause-level chunking**, then **intent routing** / **self-validation** —
   larger or robustness-oriented, sequence after the above.

Every one of these is validatable with `bench/compare.py baseline_100q.json
<new_run>.json`, which already isolates the retriever cleanly.

---

## Bottom line

On anything measurable, we're ahead — and we're the only side with a number at
all. His genuine leads are the capability/product layer, and our own benchmark now
tells us which of them to chase first: **release-aware ranking**, because it maps
straight onto our worst measured topic (LTE 1/8) and is worth more TOP-1 than
everything the last round of changes netted combined. His flashiest feature —
cross-spec MSC synthesis — turns out (per our 8/9 cross-spec retrieval) to be a
presentation layer we can add on top of retrieval we already have.

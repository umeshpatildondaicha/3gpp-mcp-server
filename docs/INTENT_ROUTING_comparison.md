# Intent routing — ours (new) vs. TejVox 3GPPilot

Focused comparison of the **intent-routing** dimension only, now that ours is
implemented. Ours is read from the code on-device (2026-07-27:
`ThreeGppToolService.classifyIntent` + `ScopeGateService` + `spec-ownership.tsv`);
theirs is inferred from his public pipeline screenshots.

---

## What each one is

**TejVox 3GPPilot** — "Intent Routing" is a first-class, model-driven stage in an
agentic pipeline:
`User Context → Intent Routing (classify · scope gate · clause-lookup / external-org)
→ Query Planner (complexity → typed tasks · owning-spec inference) → Aggregated
Retrieval → Specialized Selector → Self-Validation → Adaptation`.
The routed intent changes the **downstream path**: a curated card vs. a procedure
graph vs. grounded generation, with owning-spec inference and per-task planning.

**Ours** — intent routing is now two deterministic components in `search3gpp`, plus
tools the router points to:

1. **`classifyIntent()`** — a lexical classifier, no model call, fixed precedence:
   `OUT_OF_SCOPE → TROUBLESHOOTING → PROCEDURE → CLAUSE_LOOKUP (default)`. The
   result is surfaced as an `Intent:` hint line in the response and, for
   `PROCEDURE`, a suggestion to call `getProcedureFlow`.
2. **`ScopeGateService`** — a *knowledge-grounded* scope gate. A 48-marker
   `spec-ownership.tsv` maps markers → owning spec; a marker fires **only if that
   owning spec is absent from the index** (resolved at startup). On a hit it
   short-circuits retrieval and returns "Not answerable from this KB → use
   WebSearch," with `Confidence: none (out-of-scope)`.
3. Routed-to tools now exist: **`getProcedureFlow`** (6-layer cross-spec bundle:
   23/24/29/38/36/33) for the PROCEDURE path, and a **self-validation** tool
   (checks a drafted answer's citations against retrieved chunks).

---

## Side-by-side

| Aspect | TejVox 3GPPilot | Ours (new) | Read |
|---|---|---|---|
| Exists as a stage | Yes, first-class | **Yes, now** (was absent) | **Now at parity on presence** |
| Method | Model / LLM-driven, agentic | Lexical keyword sets, deterministic | Theirs catches phrasing ours misses; ours is free, fast, reproducible |
| Intent categories | classify + scope-gate + clause-lookup / external-org; complexity→typed tasks | PROCEDURE · CLAUSE_LOOKUP · TROUBLESHOOTING · OUT_OF_SCOPE | Comparable coverage; his "typed tasks / complexity" is finer |
| Out-of-scope detection | "scope gate" in pipeline (mechanism not shown) | **Knowledge-grounded** — fires only when the owning spec is truly missing | **Ours is arguably more rigorous** (see below) |
| What the route *does* | Actively switches downstream mode (card / procedure graph / grounded gen); owning-spec inference | Scope-gate short-circuits; PROCEDURE → suggests `getProcedureFlow`; otherwise an advisory `Intent:` hint. Does **not** change retrieval params inside `search3gpp` | **Theirs routes internally; ours advises the orchestrator to route** |
| Cross-spec procedure path | "Generate Procedure Flow" | **`getProcedureFlow`** — 6 series-scoped retrievals, grouped by layer, with "layers with evidence" coverage | Now **comparable capability**, different trigger point |
| Self-validation after routing | "Self-Validation" stage | **self-validation tool** (citations vs. evidence) | Now **comparable** |
| Cost / determinism | model call per query (latency, $, non-determinism) | zero-cost, deterministic, **unit-tested** (`RetrievalSignalsTest$IntentRouting`) | **Ours wins on cost/reproducibility** |
| Transparency | shown in the UI pipeline | `Intent:` line in output + `QLOG` for auditing every decision | Parity (different surface) |

---

## The one place ours is genuinely better: the scope gate

His scope gate is a pipeline box; ours is built on a measured failure mode his
confidence-based approach can't catch. On our corpus, questions whose **owning spec
is absent scored *higher*** than correctly-answered ones (median top score 0.905 vs
0.780) — with no true owner in the pool, one loosely-related chunk wins
uncontested. **The confidence signal is inverted, so no threshold — or model
confidence — reliably catches it.** Ours doesn't use a threshold: it *knows what it
is missing* (owning-spec table, self-disabling when the spec is later ingested). A
model-driven gate that trusts its own confidence is exposed to exactly this trap.

## Where theirs is still ahead

- **Active internal routing.** His route changes the whole downstream behavior
  (typed tasks, owning-spec inference, card vs. graph vs. generation) inside one
  call. Ours mostly *annotates* and *suggests a follow-up tool* — the actual
  cross-spec work happens only if the orchestrator then calls `getProcedureFlow`.
  Control lives in our client; in his it's internal.
- **Model classifier recall.** A lexical set misses paraphrases with none of the
  trigger words ("walk me through how the UE comes back after losing the cell" has
  no PROCEDURE keyword). His model catches those.
- **Complexity / multi-task planning.** He decomposes a complex query into typed
  sub-tasks; ours emits a single intent.

---

## Verdict for this dimension

In the earlier scorecard, intent routing was **▲ TejVox** (we had none). With this
round it moves to roughly **parity**: we now have a classifier, a scope gate, a
cross-spec procedure tool, and self-validation. The character differs — **his is
model-driven and routes internally; ours is deterministic, zero-cost, unit-tested,
and advises the orchestrator** — and our knowledge-grounded scope gate is the more
principled of the two out-of-scope mechanisms. His remaining edges are active
internal routing, model-classifier recall on unusual phrasings, and query-complexity
decomposition.

**To fully close it:** (1) let a strong intent actually change `search3gpp` behavior
(e.g. PROCEDURE auto-widens per-spec cap / runs the layered retrieval) instead of
only hinting; (2) add a cheap paraphrase fallback (embedding-similarity to intent
prototypes) for queries that miss every keyword; (3) benchmark the scope gate's
precision/recall on a held-out out-of-scope set so the abstention quality is a
measured number, not just a design argument.

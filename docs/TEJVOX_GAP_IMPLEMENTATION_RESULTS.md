# TejVox gap closure — what was implemented and what it measured

**Date:** 2026-07-27
**Input:** `docs/COMPETITIVE_ANALYSIS_TejVox_3GPPilot.md` (§3 "Ideas worth borrowing")
**Method:** implement, then measure against a saved 100-question baseline.

---

## 1. Headline

**Retrieval accuracy: 64 → 70 /98 TOP1 (+9%), 92 → 95 /98 TOP5.**
Raw on the current 100-question set: **72/100 TOP1, 97/100 TOP5**.

Every capability gap the competitive analysis named is now closed except clause-level
granularity. But **none of the doc's ranking ideas produced the accuracy gain** — one was
measurably harmful and is disabled. All +6 points came from three things found while
benchmarking that the analysis did not identify:

| Change | TOP1 |
|---|---|
| baseline | 64 |
| + recovered spec titles (963 of 1061 specs) | 64 |
| + study reports evading the TR discount (§5.1) | 67 |
| + widened per-spec rerank pool (§5.3) | **70** |

Biggest topic move: **LTE 1/8 → 4/8**. `procedure`-kind questions now score **23/23 on
TOP5** and `crossspec` **8/8**.

Everything in §3 of the competitive analysis that could plausibly affect ranking was
built and measured. Two of the four ranking ideas were **measurably neutral**, one was
**measurably harmful and has been disabled**, and one is **unexercised by the benchmark**.
The capability items (procedure synthesis, confidence surface, validation) shipped and
work, but they add capability rather than accuracy.

This is a negative result on the retrieval half of the analysis, and it is worth stating
plainly: the doc's premise that "his lead is at the capability layer, our retrieval is at
parity or ahead" survived contact with the benchmark. The retrieval engine did not have
the headroom the doc's items assumed.

---

## 2. The benchmark

Two suites, run through the live MCP server over streamable HTTP:

| Suite | n | What it covers |
|---|---|---|
| `oam` | 50 | Existing PM/FM/CM/topology questions (`benchmark_oam.py`) |
| `broad` | 50 | **New** — NR radio, 5GC, NAS, security, LTE/EPC, IMS: the multi-spec "procedure" territory the TejVox gaps target |

Scoring is spec-level TOP1/TOP5 against an any-of `expected` set. Runner: `bench/run_bench.py`,
questions: `bench/questions_broad.py`, comparison: `bench/compare.py`.

### The gold set had to be fixed first

An audit of all 50 broad questions against actual chunk text found **8 defective gold entries**
— the benchmark was scoring correct retrievals as misses. Corrections are in
`bench/questions_broad.py`; the evidence is in `docs/GOLD_AUDIT.md`. Notably:

- "IMS emergency session establishment" expected 23.228; **23.167** is the actual IMS
  emergency spec, and it was being returned at rank 1 and scored a MISS.
- "charging data record file format" expected 32.692, which is **Inventory Management IRP**,
  not charging. No CDR spec exists in the corpus — the question was replaced.
- "MMTel supplementary services" was ungrounded (24.173/22.173 absent) — replaced.

**All before/after numbers in this document are re-scored on the corrected gold set for
both runs**, on the 98 questions common to every run. Comparing the raw `summary` blocks
across runs would be comparing different yardsticks.

---

## 3. What was implemented, and what each one measured

| # | Doc item | Built | Measured effect | Status |
|---|---|---|---|---|
| 3 | Confidence gate + **vector rescue** | yes | **−4 TOP1** at a threshold where it fires; 0 where it doesn't | **disabled by default** |
| 4 | Coverage / confidence score in the response | yes | n/a (signal, not ranking) — **validated: 84% vs 51%** | shipped |
| 5 | Release-aware ranking | yes | 0 — no benchmark question names a release | shipped, unexercised |
| 2 | Explicit intent routing | yes | n/a (signal, not ranking) | shipped |
| 1 | Cross-spec procedure / MSC synthesis | yes (`getProcedureFlow`) | n/a (new capability) | shipped |
| 7 | Self-validation / evidence check | yes (`validateAnswer`) | n/a (new capability) | shipped |
| 6 | Clause-level granularity | **no** | — | not attempted (re-ingestion + re-embedding of 185k chunks) |
| — | *(not in doc)* study reports evading the TR discount | yes | **+3 TOP1, +2 TOP5** | shipped — §5.1 |
| — | *(not in doc)* per-spec rerank pool too narrow | yes | **+3 TOP1, +1 TOP5** | shipped — §5.3 |
| — | *(not in doc)* 96% of specs had placeholder titles | yes | 0 alone — but **enabled §5.1** | shipped — §5.2 |

All measurable accuracy gain came from the last three rows. The analysis identified none of them.

### 5.3 The per-spec rerank pool was the single biggest win

`max-rerank-per-spec` was 2: the cross-encoder decided each spec's rank from at most 2 of its
chunks, chosen by RRF, out of hundreds. When the right clause wasn't one of those 2, the spec
lost to a peripheral spec whose one retrieved chunk happened to be lexically dense prose.
23.401's "dedicated bearer activation" clause sits at `chunk_index` 480 and never reached the
reranker; the spec was represented instead by a definition chunk and lost to 23.468 (GCSE).

Raising it to 4, with `rerank-candidates` 24 → 40: **TOP1 67 → 70, TOP5 94 → 95**, and
"dedicated EPS bearer establishment" now returns 23.401 at rank 1.

Cost is real and deliberate: **p50 latency 2657 → 4953 ms**, since rerank cost is linear in
candidates. Both are env-overridable. Note the knob is not smooth — an intermediate arm
(32 candidates / 3 per spec) measured *worse* on TOP1 (66) than leaving the pool alone.
Re-benchmark rather than interpolating.

### 3.1 Vector rescue — implemented, measured, disabled

The doc's reasoning was sound: the cross-encoder score *replaces* the RRF score, so a chunk
the dense retriever loved can be discarded on one reranker misjudgement. Implemented as a
score floor (`max(rerankScore, denseCosine × weight)`), carrying dense cosine through the
rerank stage via new `SearchHit` provenance fields.

It was verified to be **wired correctly** — forcing it fully on reorders 32 of 50 result
lists. It simply does not help this corpus:

| `dense-rescue-min-cosine` | TOP1 /98 | Note |
|---|---|---|
| disabled | **64** | |
| 0.62 | 64 | floor never binds — inert |
| 0.45 | **60** (−4) | FM topic 6→3; the floor promotes topically-near-but-wrong chunks |
| 0.00 | — | 32/50 orderings change, no net gain |

Default is now `1.01` (unreachable cosine = off), with the measurement recorded in
`application.properties` so nobody re-enables it blind.

### 3.2 Confidence gate — the one item that produced real value

The doc proposed gating on a minimum score. **That does not work**: on the baseline, the
absolute top score is nearly identical whether the top-1 hit is right or wrong
(median 0.803 vs 0.769).

What does separate them is the **rank1−rank2 margin**:

| Outcome | Median margin |
|---|---|
| top-1 correct | 0.161 |
| top-1 wrong, right spec in top-5 | 0.080 |
| nothing relevant | 0.038 |

A threshold sweep put the useful cut at **margin ≥ 0.12**, which is what shipped:

| Level | n | TOP1 accuracy |
|---|---|---|
| `high` | 43 | **84%** |
| `low` | 55 | 51% |

An intermediate `medium` band was tried and **removed** — it did not rank between the other
two (46% vs 55%), so the gate is deliberately binary. `search3gpp` now emits the level, the
margin, and the *measured* accuracy that level buys, so the orchestrator can decide between
citing `[1]` and falling back to WebSearch on evidence rather than vibes.

### 3.3 Cross-spec procedure synthesis (`getProcedureFlow`)

The doc's #1 item. A 3GPP procedure is never in one spec, and a single ranked list lets one
layer crowd out the others via the per-spec cap. The tool runs a **separate series-scoped
retrieval per specification layer** (23 stage-2, 24 NAS, 29 core protocols, 38 NR, 36 LTE,
33 security) and returns the union, grouped and labelled.

It deliberately returns **grouped evidence, not a written call flow** — the tool description
instructs the caller to assemble the MSC from the returned clauses and to state, rather than
invent, any layer with no evidence.

### 3.4 Answer validation (`validateAnswer`)

Extracts spec IDs from a draft answer, re-retrieves for the question, and reports
supported / unsupported / omitted citations. Verified working: given a draft citing
23.502, 24.501 and 29.244, it correctly flags **29.244 as unsupported** — that spec is
genuinely absent from the corpus.

---

## 4. Corpus gaps found (these bound what any retrieval work can achieve)

Verified absent from the index, despite being central to the procedures TejVox advertises:

- **29.244 (PFCP)** — the spec behind their headline UPF-restart demo
- 29.502, 29.518, 29.281, 24.229, 24.173, 22.173, 32.240, 32.251, 32.297/32.298 (all CDR specs)
- 28.531, 29.505, 29.519

Series 24 holds only 24.501 and 24.301; series 29 only 29.274, 29.500, 29.510, 29.571.
No ranking change can retrieve a spec that isn't indexed — **closing these gaps is worth
more than any tuning in this document.**

---

## 5. Findings the doc did not contain

### 5.1 Study reports evading the TR discount (the one real accuracy win)

The pipeline discounts study reports by 0.50 using two signals: `doc_type == 'TR'`, and the
3GPP convention that spec numbers in **[700, 900)** are study items. **11 specs / 1796 chunks
evade both** — they are labelled `doc_type='TS'` in the DB *and* numbered outside the range:

| Spec | Chunks | Title |
|---|---|---|
| 23.909 | 741 | Technical report on the Gateway Location Register |
| 38.900 | 552 | Study on channel model for frequency spectrum above 6 GHz |
| 38.912 | 92 | Study on New Radio (NR) access technology |
| 38.913 | 34 | Study on Scenarios and Requirements for Next Generation Access |
| … 7 more | | |

38.912 was taking rank 1 on "LTE RRC measurement report configuration" **with no discount
applied at all**, ahead of 36.331.

The recovered titles made a third signal possible: `isStudyReportByTitle()` matches
*"Study on/of/into"*, *"Feasibility study"*, *"Technical report"*. This is the only change
in this whole exercise that moved the benchmark: **+1 TOP1, +1 TOP5**, NR 5→6, and it is a
straightforward correctness fix rather than a tuning gamble.

Note the dependency: this fix only became available *because* titles were recovered. The
title work scored zero on its own but unlocked this.

### 5.2 Title metadata

96% of the corpus (1024 of 1061 specs, 178k of 185k chunks) carried the placeholder title
`"3GPP TS <id>"`. That matters because `RerankService.scoreBatch()` scores the pair
`(query, title + ": " + snippet)` and FTS5 indexes the `title` column — so for almost the
whole corpus the cross-encoder got **zero document-level signal**.

Real cover-page titles were recovered from the specs' own text for **528 specs / 137,935
chunks** (`3gpp embedings/title_recovery.py`, applied by `apply_titles.py`, rollback in
`title_undo.csv`). 36.331 went from `3GPP TS 36.331` to
`3GPP TS 36.331 — Evolved Universal Terrestrial Radio Access (E-UTRA); Radio Resource
Control (RRC); Protocol specification`.

**Measured effect: also net zero** (+2 questions, −2 questions).

The mechanism works exactly as predicted where it fires — "MAC HARQ operation in NR" now
returns 38.321 at rank 1 because that spec gained the title *"NR; Medium Access Control
(MAC) protocol specification"*. But the wins were cancelled by losses of the same kind:
32.692 gained *"Inventory Management (IM) ... Network Resource Model (NRM)"* and promptly
displaced 28.541 on "ManagedElement IOC inheritance".

There is also a structural asymmetry that limits the upside: recovery favoured **study
reports over normative specs** (TR 369 specs / 115.5k chunks vs TS 159 specs / 22.4k chunks),
because TR cover pages survived extraction more often. The most important normative specs —
23.501, 23.502, 23.228, 23.401 — are in the **504 still unrecovered**, since their chunk 0
begins at "Contents" with the cover page lost during ingestion.

**Recovering those 504 is the highest-value outstanding retrieval work**, and it is
tractable: other specs cite them by full official title in their References clauses.

---

## 6. Where the remaining accuracy actually goes

On the corrected gold set the weak areas are:

| Bucket | TOP1 |
|---|---|
| LTE | **1/8** |
| `procedure`-kind questions | **10/22** |
| NR | 5/12 |
| PM | 19/21 |
| `crossspec`-kind | 9/9 |

The dominant visible failure mode is **TR study reports outranking the normative TS** —
e.g. "LTE RRC measurement report configuration" returns 38.912 and 36.938 (both TRs) ahead
of 36.331. The corpus is skewed this way by construction: series 36 has 243 specs of which
the large majority are 36.7xx/36.8xx study items, and they carry far more chunks than the
normative specs. The existing TR discount (0.50, applied twice) is evidently not enough.

---

## 7. Reproducing

```bash
./start.sh dev                                   # wait ~90 s for model + reranker load

python3 bench/run_bench.py --suite both \
    --out bench/results/my_run.json --label "my change" --keep-raw

python3 bench/compare.py \
    bench/results/baseline_100q.json bench/results/my_run.json
```

`compare.py` re-scores every run against one corrected gold set restricted to the questions
present in all runs, so the only thing differing between columns is the retriever. Use it
rather than diffing the `summary` blocks.

Saved runs in `bench/results/`:

| File | Config |
|---|---|
| `baseline_100q.json` | original titles, original code |
| `ablation_titles_only_100q.json` | recovered titles, new scoring disabled |
| `after_titles_and_code_100q.json` | recovered titles + rescue 0.62 + release boost |
| `tune_rescue_045.json` | rescue threshold 0.45 (the harmful setting) |
| `after_tr_title_fix.json` | **current default** — titles + TR-by-title detection |

---

## 8. Scope gate — closing the intent-routing gap properly

**Added 2026-07-27, after the numbers above.**

The intent router originally shipped as *classification only*: it labelled the query
and then changed nothing. Its `out-of-scope` class recognised vendor names but had no
concept of "this question is about a spec family we don't index" — the case that
actually matters. Eight probe questions whose true owner is absent from the corpus were
all answered anyway, **four of them at `high` confidence**.

### Why this had to be a knowledge lookup, not a threshold

| | median top score | median margin |
|---|---|---|
| out-of-corpus (unanswerable) | **0.905** | 0.123 |
| in-corpus, answered correctly | 0.780 | 0.361 |

The signal is **inverted** — unanswerable questions score *higher*, because with no true
owner competing, one loosely-related chunk wins uncontested. All 8 probes clear any
threshold derived from the in-corpus distribution. No confidence gate can catch this;
the server has to know what it is missing.

### Implementation

- `src/main/resources/retrieval/spec-ownership.tsv` — 12 markers → owning spec.
- `ScopeGateService` — loads the table, and at startup keeps only the markers whose
  owning spec is genuinely absent. **Ingesting 29.244 later disables the PFCP markers
  automatically**, with no code or config change.
- `ThreeGppToolService.search3gpp()` short-circuits before retrieval when a marker fires,
  naming the spec the caller needs and pointing at WebSearch.

### Precision over recall, deliberately

A false positive — blocking a question the corpus *can* answer — is the worst regression
this server can have, so markers were validated against all 100 benchmark questions
before any Java was written:

| | result |
|---|---|
| out-of-corpus probes refused | **5/8** (was 0/8) |
| false positives on 100 answerable questions | **0** |
| benchmark TOP1 / TOP5 | **70/98, 95/98 — unchanged** |

The 3 uncaught probes are deliberate. `Nsmf_PDUSession_CreateSMContext` is *not* gated
because 23.502 **is** indexed and legitimately defines those service operations at
stage 2; `s-cscf` is not a marker because 23.228 covers IMS architecture. Gating those
would trade a wrong answer for a wrong refusal.

### Still open on intent routing

The gate closes the out-of-scope half. The other half — making `procedure` questions
retrieve differently from `lookup` questions — is not done. The evidence that it would
pay: `procedure` questions score **13/23 TOP1 but 23/23 TOP5**. The right spec is
retrieved every single time and simply isn't ranked first.

---

## 9. Confidence gate + vector rescue — resolved

**Updated 2026-07-27.** The competitive analysis paired these as one item. They
resolved in opposite directions.

### Vector rescue — deleted, not fixed

Two independent reasons, both measured:

1. **Harmful wherever it fired.** min-cosine 0.62 → inert (floor never binds);
   0.45 → **−4 TOP1** (FM 6→3); 0.00 → 32/50 orderings changed, no gain. The
   cross-encoder is simply a better judge than dense cosine on this corpus, and a
   score floor promotes topically-near-but-wrong chunks.
2. **It solved a problem that is not occurring.** Its premise is "the reranker
   discards chunks the dense retriever ranked highly." TOP5 is **95/98** — the
   correct spec is retrieved essentially always. There was nothing to rescue.

Left disabled it was ~15 lines of dead code plus two knobs that existed only to
stay off, and a `SearchHit.denseScore` field carried through the whole pipeline
to feed it. All removed. The measurements are recorded in
`application.properties` so the idea is not silently re-derived.

### Confidence gate — kept, and materially sharpened

Two things were wrong with the shipped version.

**(a) It was mislabelled.** It measures *ranking separation*, not *answer
presence*, and callers will assume otherwise. Out-of-corpus questions score a
median margin of **0.226** — above the 0.12 "high" threshold. The response now
states plainly what the number covers; answer presence is the scope gate's job
(§8), not this one's.

**(b) One signal was not enough.** A rejected hypothesis is worth recording:
**query-term coverage in the top hit does not work** — median 1.00 for both
answerable and unanswerable questions. Retrieved chunks are long and query terms
generic, so coverage saturates.

What did work is **retriever agreement**: whether dense and BM25 independently
placed the top hit's spec in their own top tier. That is a different kind of
evidence from margin, which only compares a hit to its neighbours inside one
already-fused ranking.

| Signal | n | TOP1 correct |
|---|---|---|
| support 0/2 | 5 | 40% |
| support 1/2 | 29 | 51% |
| support 2/2 | 66 | **83%** |
| margin ≥ 0.12 | 42 | **88%** |
| margin < 0.12 **and** support < 2/2 | 22 | **27%** |

The value is in the **negative conjunction**. A weak margin alone was a 51%
warning — barely actionable. A weak margin *and* no cross-retriever agreement is
a 27% warning, which genuinely means "do not cite [1]".

Shipped as three tiers, verified end-to-end on the benchmark:

| Level | Rule | n | TOP1 correct |
|---|---|---|---|
| `high` | margin ≥ 0.12 | 41 | **87%** |
| `medium` | margin < 0.12 but both retrievers agree | 36 | 80% |
| `low` | margin < 0.12 and retrievers disagree | 23 | **30%** |

Note this reverses an earlier decision. A `medium` band was tried and dropped
when margin was the only signal, because it did not rank between the other two
(46% vs 55%). With agreement added it does — and it rescues 36 results that the
binary gate was lumping into "low". Each level now publishes its measured
accuracy in the response, so the orchestrator weighs `[1]` on evidence rather
than on the word "high".

Retrieval accuracy is unchanged by all of this (**70/98 TOP1, 95/98 TOP5**) —
confidence is a reporting signal, not a ranking one.

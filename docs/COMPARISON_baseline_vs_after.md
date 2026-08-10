# 3GPP MCP — retrieval comparison: baseline vs. TejVox-gap changes

Scored with your `bench/compare.py`, which re-scores **every** run from its saved
`specs` list against one corrected gold set, restricted to the **98 questions
present in all runs** (2 ungrounded questions excluded). So the only thing that
differs between columns is the retriever, not the yardstick.

## Headline

| Metric | Baseline (pre-gap work) | Final (all fixes) | Δ |
|---|---|---|---|
| **TOP-1** | 64/98 (65.3%) | **67/98 (68.4%)** | **+3** |
| **TOP-5** | 92/98 (93.9%) | **94/98 (95.9%)** | **+2** |

Net positive, and the gains land in the areas we set out to close against TejVox
(NR, NAS, procedures), not in our already-strong OAM topics.

## Progression across every stage

| Stage | TOP-1 | TOP-5 | Note |
|---|---|---|---|
| baseline | 64/98 | 92/98 | pre-gap-work reference |
| titles recovered + confidence/rescue/rerank | 64/98 (+0) | 92/98 (+0) | plumbing in, no net move yet |
| rescue 0.45/0.85 | 60/98 (−4) | 89/98 (−3) | **regressed** — threshold too aggressive; correctly backed out |
| titles + TR-by-title detection | 65/98 (+1) | 93/98 (+1) | TR 9xx detection helps |
| **all titles + TR 9xx band + rescue order** | **67/98 (+3)** | **94/98 (+2)** | final |

The `rescue 0.45/0.85` experiment is the one to remember: it cost 4 TOP-1
(FM 6→3, PM 19→17). Whatever the final "rescue order" does is strictly better —
keep the 0.45/0.85 thresholds retired.

## Where the +3 came from (per-topic TOP-1, baseline → final)

| Topic | n | Baseline | Final | Δ |
|---|---|---|---|---|
| NR | 12 | 4 | **6** | +2 |
| NAS | 5 | 4 | **5** | +1 |
| FM | 10 | 6 | **7** | +1 |
| 5GC | 12 | 9 | 8 | −1 |
| CM | 14 | 9 | 9 | 0 |
| PM | 21 | 19 | 19 | 0 |
| LTE | 8 | 1 | 1 | 0 |
| SEC | 5 | 4 | 4 | 0 |
| MGMT | 4 | 3 | 3 | 0 |
| TOPO | 5 | 3 | 3 | 0 |
| IMS | 2 | 2 | 2 | 0 |

By question **kind**: lookup 45→48 (+3), procedure 10→11 (+1), cross-spec 9→8 (−1).

Gained TOP-1 (5): *CSI reporting configuration NR*, *MAC HARQ operation in NR*,
*NAS security mode command procedure*, *clear alarm procedure cleared by
operator*, *network slice subnet management lifecycle*.

## Two regressions to fix (both near-miss ordering flips, not retrieval misses)

Both still have the right spec in the top-5 — the rescue re-ordering just bumped
a non-gold spec to #1:

- **AMF UE context management over N2** — baseline ranked `23.501` (gold) #1;
  final ranks `38.410` #1, pushing `23.501` to #2. Gold present at rank 2.
- **NSSI provisioning workflow** — baseline ranked `28.545` (gold) #1; final
  ranks `23.502` #1, `28.545` drops to #2. Gold present at rank 2.

Fix: the rescue/RRF tie-break is favouring interface specs (38.410) and
procedural specs (23.502) over the owning spec on these two. A small
owning-spec/alias-pin nudge recovers both without touching the wins.

## Biggest remaining weakness: LTE (1/8 TOP-1) — systematic, and high-value

LTE is unchanged at **1/8** and is the clearest single opportunity. Root cause is
visible in the raw results: **NR (38-series) chunks are crowding out LTE
(36-series) chunks** on LTE queries.

| LTE question | #1 returned | Gold | Problem |
|---|---|---|---|
| LTE RRC measurement report configuration | `38.321` | `36.331` | NR MAC returned for an LTE RRC query |
| LTE MAC random access procedure | `38.321` | `36.321` | NR MAC ranked above the LTE MAC spec (which is at rank 2) |
| dedicated EPS bearer establishment | `23.468` | `23.401`, `24.301` | owning specs absent from top-5 |
| S1AP initial UE message | `23.401` | `36.413` | gold at rank 3 |
| X2 based handover in LTE | `23.216` | `23.401`, `36.300` | gold at rank 4 |

Five of the eight already have the correct 36-series spec in the top-5 — they're
losing on **rank**, not recall. This is exactly the **release/technology-aware
ranking** dimension the TejVox analysis flagged: detect "LTE"/"EPS"/"E-UTRAN" or a
36-series intent in the query and down-weight 38-series (and vice-versa). One
release-aware re-rank pass would likely convert most of LTE from 1/8 toward 5–6/8,
i.e. another **+4–5 TOP-1 overall** — larger than everything the gap-work has
netted so far.

## Recommended next moves, in priority order

1. **Release/technology-aware ranking** (LTE↔NR disambiguation). Highest expected
   lift (~+4–5 TOP-1) and directly closes a known TejVox advantage.
2. **Owning-spec tie-break nudge** to recover the 2 regressions (AMF-N2, NSSI)
   without disturbing the wins.
3. Keep the `rescue 0.45/0.85` thresholds retired.
4. Re-run `bench/compare.py baseline_100q.json <new_run>.json` after each change —
   the harness already isolates the retriever cleanly, so every delta is real.

_Cross-spec dropped 9→8: worth a glance, but it's a single question and the
cross-spec set is only 9, so it's within noise — the release-aware work above
matters more._

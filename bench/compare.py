#!/usr/bin/env python3
"""
Compare N saved benchmark runs on a single, consistent gold set.

Runs taken at different times may have been scored against different `expected`
sets (the gold set was corrected mid-experiment) and may not even contain the
same questions (two ungrounded questions were replaced). Comparing the `summary`
blocks in those files directly would be comparing different yardsticks.

This re-scores every run from its saved `specs` list against ONE corrected gold
set, restricted to the questions present in ALL runs — so the only thing that
differs between columns is the retriever.

Usage:
    python3 bench/compare.py run_a.json run_b.json run_c.json
"""
import json
import sys
from pathlib import Path

# Corrected gold, from an audit of every question against actual chunk text in
# 3gpp_certified.db. See bench/GOLD_AUDIT.md for the evidence behind each.
GOLD_OVERRIDE = {
    "IMS emergency session establishment": {"23.167"},
    "UDM UDR subscription data types": {"23.501", "23.502"},
    "EPS AKA authentication vector generation": {"33.401"},
    "network slice subnet instance NSSI management": {"28.541", "28.530"},
    "AMF UE context management over N2": {"23.501", "23.502", "38.413"},
    "Xn handover procedure between gNBs": {"38.423", "38.300", "38.401"},
}


def load(path):
    d = json.loads(Path(path).read_text())
    out = {}
    for r in d["results"]:
        out[r["q"]] = r
    return d.get("label") or Path(path).stem, out


def score(rec):
    expected = set(GOLD_OVERRIDE.get(rec["q"], rec["expected"]))
    specs = rec.get("specs", [])
    return (bool(specs) and specs[0] in expected,
            any(s in expected for s in specs[:5]))


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    runs = [load(p) for p in sys.argv[1:]]
    common = set(runs[0][1])
    for _, rs in runs[1:]:
        common &= set(rs)
    common = sorted(common)
    dropped = sorted(set(runs[0][1]) - set(common))

    print(f"Questions common to all {len(runs)} runs: {len(common)}")
    if dropped:
        print(f"Excluded (not present in every run): {len(dropped)}")
        for q in dropped:
            print(f"  - {q}")
    print(f"Gold corrections applied to all runs: {len(GOLD_OVERRIDE)}\n")

    scored = []
    for label, rs in runs:
        s = {q: score(rs[q]) for q in common}
        scored.append((label, s))

    n = len(common)
    print(f"{'run':38s} {'TOP1':>12s} {'TOP5':>12s}")
    base1 = base5 = None
    for label, s in scored:
        t1 = sum(v[0] for v in s.values())
        t5 = sum(v[1] for v in s.values())
        if base1 is None:
            base1, base5 = t1, t5
            d1 = d5 = ""
        else:
            d1, d5 = f"({t1-base1:+d})", f"({t5-base5:+d})"
        print(f"{label[:38]:38s} {t1:>4d}/{n:<3d}{d1:>5s} {t5:>4d}/{n:<3d}{d5:>5s}")

    # Per-topic, first run as the reference column.
    topics = {}
    for q in common:
        topics.setdefault(runs[0][1][q]["topic"], []).append(q)
    print(f"\n{'topic':10s} {'n':>3s} " + " ".join(f"{lb[:16]:>17s}" for lb, _ in scored))
    for t, qs in sorted(topics.items()):
        cells = []
        for _, s in scored:
            cells.append(f"{sum(s[q][0] for q in qs):>8d} top1")
        print(f"{t:10s} {len(qs):>3d} " + " ".join(f"{c:>17s}" for c in cells))

    kinds = {}
    for q in common:
        kinds.setdefault(runs[0][1][q].get("kind", "lookup"), []).append(q)
    print(f"\n{'kind':10s} {'n':>3s} " + " ".join(f"{lb[:16]:>17s}" for lb, _ in scored))
    for t, qs in sorted(kinds.items()):
        cells = [f"{sum(s[q][0] for q in qs):>8d} top1" for _, s in scored]
        print(f"{t:10s} {len(qs):>3d} " + " ".join(f"{c:>17s}" for c in cells))

    if len(scored) >= 2:
        first, last = scored[0], scored[-1]
        gained = [q for q in common if not first[1][q][0] and last[1][q][0]]
        lost = [q for q in common if first[1][q][0] and not last[1][q][0]]
        print(f"\nvs first run — GAINED top1 ({len(gained)}):")
        for q in gained:
            print(f"  + {q}")
        print(f"vs first run — LOST top1 ({len(lost)}):")
        for q in lost:
            print(f"  - {q}")


if __name__ == "__main__":
    main()

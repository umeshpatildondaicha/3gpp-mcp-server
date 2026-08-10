#!/usr/bin/env python3
"""
Re-score saved benchmark runs against a corrected gold set, and diff two runs.

Both runs were saved with --keep-raw, so the retrieved spec lists are already on
disk: correcting the gold set is a pure re-scoring pass, no server needed. That
also means the correction is applied identically to both runs, so the comparison
stays fair.

Corrections come from an audit of every question's `expected` set against the
actual chunk text in 3gpp_certified.db (see bench/GOLD_AUDIT.md).

Usage:
    python3 bench/rescore.py bench/results/baseline_100q.json \
                             bench/results/after_titles_and_code_100q.json
"""
import json
import sys
from pathlib import Path

# ── Gold-set corrections, keyed by question text ─────────────────────────────
# Each entry: question -> corrected `expected` set.
CORRECTIONS = {
    # 23.167 IS the IMS emergency sessions spec; 23.228's "emergency" chunks are
    # TOC lines and cross-references only. The original gold scored a correct
    # 23.167 retrieval as a MISS.
    "IMS emergency session establishment": {"23.167"},
    # 29.571 in this corpus is raw SBI OpenAPI YAML — 0 chunks mention UDR and 0
    # mention "subscription data". The stage-2 owners are 23.501/23.502.
    "UDM UDR subscription data types": {"23.501", "23.502"},
    # 24.301 has exactly one "authentication vector" chunk, about the identity
    # procedure after an auth failure — not AV generation. 33.401 owns it.
    "EPS AKA authentication vector generation": {"33.401"},
    # 28.545 is Fault Supervision; its NSSI mentions are alarm handling only.
    "network slice subnet instance NSSI management": {"28.541", "28.530"},
    # 23.502 holds the N2 UE-context stage-2 flows (86 matching chunks vs 43 in
    # 23.501); retrieving it was correct but scored a MISS.
    "AMF UE context management over N2": {"23.501", "23.502", "38.413"},
    # 38.401 clause 8 carries the overall Xn-based inter-NG-RAN mobility procedures.
    "Xn handover procedure between gNBs": {"38.423", "38.300", "38.401"},
}

# Questions with NO valid answer anywhere in the corpus. Scoring them punishes
# the retriever for a corpus gap, so they are excluded from the totals rather
# than silently counted as misses. Replacements are staged in questions_broad.py
# for future runs; they can't be applied retroactively to saved runs.
UNGROUNDED = {
    # 32.692 is Inventory Management IRP, not charging. No CDR spec
    # (32.240/32.297/32.298) exists in the corpus at all.
    "charging data record file format and transfer":
        "no CDR spec in corpus; 32.692 is Inventory Management IRP",
    # 24.173 / 22.173 absent; 23.228's MMTel chunks are Rel-19 IMS Data Channel,
    # not supplementary-service architecture.
    "MMTel supplementary services architecture":
        "24.173/22.173 absent; 23.228 does not cover this",
}


def rescore(path):
    data = json.loads(Path(path).read_text())
    out = []
    for r in data["results"]:
        q = r["q"]
        if q in UNGROUNDED:
            continue
        expected = set(CORRECTIONS.get(q, r["expected"]))
        specs = r.get("specs", [])
        out.append({
            **{k: r[k] for k in ("i", "suite", "q", "topic", "kind") if k in r},
            "expected": sorted(expected),
            "specs": specs,
            "top1": bool(specs) and specs[0] in expected,
            "top5": any(s in expected for s in specs[:5]),
        })
    return data.get("label", Path(path).stem), out


def bucket(results, key):
    store = {}
    for r in results:
        d = store.setdefault(r.get(key, "?"), {"n": 0, "top1": 0, "top5": 0})
        d["n"] += 1
        d["top1"] += int(r["top1"])
        d["top5"] += int(r["top5"])
    return store


def totals(results):
    return (sum(r["top1"] for r in results),
            sum(r["top5"] for r in results),
            len(results))


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    (label_a, a), (label_b, b) = rescore(sys.argv[1]), rescore(sys.argv[2])
    by_q_a = {r["q"]: r for r in a}
    by_q_b = {r["q"]: r for r in b}

    t1a, t5a, n = totals(a)
    t1b, t5b, _ = totals(b)

    print(f"Excluded as ungrounded ({len(UNGROUNDED)}): "
          + "; ".join(f"{q!r} — {why}" for q, why in UNGROUNDED.items()))
    print(f"Gold corrections applied: {len(CORRECTIONS)}")
    print(f"Scored questions: {n}\n")

    print(f"{'':34s} {'BEFORE':>9s} {'AFTER':>9s} {'DELTA':>7s}")
    print(f"{'TOP1':34s} {t1a:>4d}/{n:<4d} {t1b:>4d}/{n:<4d} {t1b-t1a:>+7d}")
    print(f"{'TOP5':34s} {t5a:>4d}/{n:<4d} {t5b:>4d}/{n:<4d} {t5b-t5a:>+7d}")

    for dim in ("suite", "topic", "kind"):
        ba, bb = bucket(a, dim), bucket(b, dim)
        print(f"\nBy {dim}:")
        print(f"  {'':12s} {'n':>3s}  {'top1 before→after':>20s}  {'top5 before→after':>20s}")
        for k in sorted(ba):
            da, db = ba[k], bb.get(k, {"top1": 0, "top5": 0})
            d1, d5 = db["top1"] - da["top1"], db["top5"] - da["top5"]
            print(f"  {k:12s} {da['n']:>3d}  {da['top1']:>8d} → {db['top1']:<3d} {d1:>+4d}"
                  f"  {da['top5']:>8d} → {db['top5']:<3d} {d5:>+4d}")

    print("\n── Per-question changes ──")
    gained = [q for q in by_q_a if not by_q_a[q]["top1"] and by_q_b[q]["top1"]]
    lost = [q for q in by_q_a if by_q_a[q]["top1"] and not by_q_b[q]["top1"]]
    print(f"\nGAINED top1 ({len(gained)}):")
    for q in gained:
        print(f"  + {q}\n      before={by_q_a[q]['specs'][:3]}  after={by_q_b[q]['specs'][:3]}")
    print(f"\nLOST top1 ({len(lost)}):")
    for q in lost:
        print(f"  - {q}\n      before={by_q_a[q]['specs'][:3]}  after={by_q_b[q]['specs'][:3]}")


if __name__ == "__main__":
    main()

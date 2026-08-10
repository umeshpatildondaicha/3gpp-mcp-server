#!/usr/bin/env python3
"""
Benchmark runner for getProcedureFlow.

Usage:
    python3 bench/run_procflow.py --out bench/results/procflow_baseline.json --label baseline

Reports coverage / noise / honesty per procedure and in aggregate. See
procedures_eval.py for what each metric means and why.

Study-report detection reads doc_type straight from the DB rather than guessing
from the spec number, because this corpus has 63 specs in the 9xx band that are
genuine study reports while carrying doc_type='TS' — and 602 correctly labelled
TRs. Neither signal alone is sufficient.
"""
import argparse
import json
import re
import sqlite3
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

from procedures_eval import PROCEDURES, is_test_spec          # noqa: E402
from run_bench import init_session, post                      # noqa: E402

DB = HERE.parent / "3gpp_kb" / "3gpp_certified.db"
LAYER_RE = re.compile(r"^=== (.+?) \(series (\S+)\) ===", re.M)
HIT_RE = re.compile(r"^\s{2}(\S+)\s+\|\s+(\S+)\s+\|(?:\s+chunk\s+(-?\d+)\s+\|)?\s+score\s+([\d.]+)", re.M)
EMPTY_LINE_RE = re.compile(r"^No strong evidence in:\s*(.+)$", re.M)
STUDY_TITLE_RE = re.compile(r"\b(study on|study of|study into|feasibility study|technical report)\b", re.I)


def load_doc_types():
    con = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
    out = {}
    for spec_id, doc_type, title in con.execute(
            "SELECT spec_id, doc_type, title FROM chunks GROUP BY spec_id"):
        is_tr = (doc_type or "").upper() == "TR" or bool(STUDY_TITLE_RE.search(title or ""))
        out[spec_id] = is_tr
    con.close()
    return out


def call(sid, tool, args):
    sid2, resp = post(sid, {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                            "params": {"name": tool, "arguments": args}})
    if resp.get("error"):
        raise RuntimeError(resp["error"])
    text = resp["result"]["content"][0]["text"]
    if text.startswith('"') and text.endswith('"'):
        try:
            text = json.loads(text)
        except json.JSONDecodeError:
            pass
    return sid2, text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--label", default="")
    ap.add_argument("--per-layer", type=int, default=3)
    args = ap.parse_args()

    is_tr = load_doc_types()
    sid = init_session()
    results = []

    print(f"{'procedure':34s} {'cov':>7s} {'noise':>7s} {'specs':>6s} {'empty':>6s} {'chars':>7s}")
    print("-" * 78)
    for item in PROCEDURES:
        sid, text = call(sid, "getProcedureFlow", {
            "procedure": item["procedure"],
            "technology": item["technology"],
            "perLayer": args.per_layer,
        })
        specs = [m.group(1) for m in HIT_RE.finditer(text)]
        uniq = sorted(set(specs))
        expected = set(item["expected"])
        found = expected & set(uniq)
        coverage = len(found) / len(expected) if expected else 1.0

        noisy = [s for s in uniq if is_test_spec(s) or is_tr.get(s, False)]
        noise = len(noisy) / len(uniq) if uniq else 0.0
        m = EMPTY_LINE_RE.search(text)
        empty_layers = len([x for x in m.group(1).split(";") if x.strip()]) if m else 0

        results.append({
            "procedure": item["procedure"],
            "expected": sorted(expected),
            "missing": sorted(expected - found),
            "specs": uniq,
            "noisy_specs": noisy,
            "coverage": round(coverage, 3),
            "noise": round(noise, 3),
            "empty_layers": empty_layers,
            "chars": len(text),
        })
        print(f"{item['procedure'][:32]:34s} {coverage:>6.0%} {noise:>6.0%} "
              f"{len(uniq):>6d} {empty_layers:>6d} {len(text):>7d}")

    n = len(results)
    summary = {
        "coverage": round(sum(r["coverage"] for r in results) / n, 3),
        "noise": round(sum(r["noise"] for r in results) / n, 3),
        "avg_specs": round(sum(len(r["specs"]) for r in results) / n, 1),
        "empty_layers_total": sum(r["empty_layers"] for r in results),
        "avg_chars": int(sum(r["chars"] for r in results) / n),
        "fully_covered": sum(1 for r in results if r["coverage"] == 1.0),
        "total": n,
    }
    print("-" * 78)
    print(f"{'MEAN':34s} {summary['coverage']:>6.0%} {summary['noise']:>6.0%} "
          f"{summary['avg_specs']:>6.1f} {summary['empty_layers_total']:>6d} {summary['avg_chars']:>7d}")
    print(f"\nfully covered: {summary['fully_covered']}/{n} procedures")
    noisy_all = sorted({s for r in results for s in r["noisy_specs"]})
    if noisy_all:
        print(f"noise specs returned: {', '.join(noisy_all)}")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"label": args.label, "summary": summary,
                               "results": results}, indent=2))
    print(f"wrote {out}")


if __name__ == "__main__":
    main()

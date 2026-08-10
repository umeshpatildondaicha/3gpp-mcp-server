#!/usr/bin/env python3
"""
Generic MCP retrieval benchmark runner.

Drives a running 3gpp-mcp-server over MCP streamable HTTP and scores spec-level
TOP1 / TOP5 against each question's `expected` set (any-of match).

Usage:
    python3 bench/run_bench.py --suite oam   --out bench/results/oam_baseline.json
    python3 bench/run_bench.py --suite broad --out bench/results/broad_baseline.json
    python3 bench/run_bench.py --suite both  --out bench/results/all_after.json --label after

Suites:
    oam   — the existing 50 OAM/PM/FM/CM questions from benchmark_oam.py
    broad — the 50 NR / 5GC / NAS / security / LTE / IMS questions
    both  — 100 questions, reported per-suite and combined

The runner also records, per question, whatever confidence/coverage header the
server emits (once that lands) so before/after runs stay directly comparable.
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(HERE))

URL = os.environ.get("MCP_URL", "http://localhost:3000/mcp")
HEADERS_BASE = {
    "Content-Type": "application/json",
    "Accept": "application/json, text/event-stream",
}


# ── MCP plumbing ─────────────────────────────────────────────────────────────

def parse_sse(text):
    payload = [line[5:].lstrip() for line in text.splitlines() if line.startswith("data:")]
    return json.loads("\n".join(payload)) if payload else json.loads(text)


def post(session_id, body):
    headers = dict(HEADERS_BASE)
    if session_id:
        headers["Mcp-Session-Id"] = session_id
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=180) as resp:
        sid = resp.headers.get("Mcp-Session-Id") or session_id
        body_text = resp.read().decode("utf-8", errors="replace")
        if "text/event-stream" in resp.headers.get("Content-Type", ""):
            return sid, parse_sse(body_text)
        if not body_text.strip():
            return sid, None
        return sid, json.loads(body_text)


def init_session():
    sid, _ = post(None, {
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                   "clientInfo": {"name": "bench-runner", "version": "1"}},
    })
    post(sid, {"jsonrpc": "2.0", "method": "notifications/initialized"})
    return sid


def call_search(sid, query, k=5):
    sid2, resp = post(sid, {
        "jsonrpc": "2.0", "id": 2, "method": "tools/call",
        "params": {"name": "search3gpp", "arguments": {"query": query, "topK": k}},
    })
    if resp.get("error"):
        return sid2, resp["error"], None
    content = resp["result"]["content"][0]["text"]
    # Spring AI double-encodes the tool string output.
    if content.startswith('"') and content.endswith('"'):
        try:
            content = json.loads(content)
        except json.JSONDecodeError:
            pass
    return sid2, None, content


# ── Result parsing ───────────────────────────────────────────────────────────

SPEC_RE = re.compile(r"^\[(\d+)\]\s+([^\s|]+)\s+\|", re.M)
# Confidence/coverage header emitted by formatHits once the confidence gate lands.
# Absent on the baseline run — recorded as null so the two runs stay comparable.
CONF_RE = re.compile(r"^Confidence:\s*(\w+)", re.M)
MARGIN_RE = re.compile(r"margin=([0-9.]+)", re.M)
TOPSCORE_RE = re.compile(r"^\[1\][^\n]*Score:\s*([0-9.]+)", re.M)


def extract_specs(text):
    return [m.group(2) for m in SPEC_RE.finditer(text)]


def extract_signals(text):
    conf = CONF_RE.search(text)
    marg = MARGIN_RE.search(text)
    top = TOPSCORE_RE.search(text)
    return {
        "confidence": conf.group(1) if conf else None,
        "margin": float(marg.group(1)) if marg else None,
        "top_score": float(top.group(1)) if top else None,
    }


# ── Suite loading ────────────────────────────────────────────────────────────

def load_suite(name):
    if name == "oam":
        from benchmark_oam import QUESTIONS
        return [dict(item, suite="oam", kind=item.get("kind", "lookup")) for item in QUESTIONS]
    if name == "broad":
        from questions_broad import QUESTIONS
        return [dict(item, suite="broad") for item in QUESTIONS]
    if name == "both":
        return load_suite("oam") + load_suite("broad")
    raise SystemExit(f"unknown suite: {name}")


# ── Scoring ──────────────────────────────────────────────────────────────────

def bucket_add(store, key, top1, top5):
    d = store.setdefault(key, {"n": 0, "top1": 0, "top5": 0})
    d["n"] += 1
    d["top1"] += int(top1)
    d["top5"] += int(top5)


def summarize(results):
    total = len(results)
    top1 = sum(1 for r in results if r.get("top1"))
    top5 = sum(1 for r in results if r.get("top5"))
    by_topic, by_kind, by_suite = {}, {}, {}
    for r in results:
        bucket_add(by_topic, r["topic"], r.get("top1"), r.get("top5"))
        bucket_add(by_kind, r.get("kind", "lookup"), r.get("top1"), r.get("top5"))
        bucket_add(by_suite, r["suite"], r.get("top1"), r.get("top5"))
    return {
        "total": total,
        "top1": top1,
        "top5": top5,
        "top1_pct": round(100.0 * top1 / total, 1) if total else 0.0,
        "top5_pct": round(100.0 * top5 / total, 1) if total else 0.0,
        "by_suite": by_suite,
        "by_topic": by_topic,
        "by_kind": by_kind,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--suite", default="both", choices=["oam", "broad", "both"])
    ap.add_argument("--out", required=True, help="output JSON path")
    ap.add_argument("--label", default="", help="free-text label stored in the file (e.g. 'baseline')")
    ap.add_argument("--topk", type=int, default=5)
    ap.add_argument("--keep-raw", action="store_true",
                    help="store full response text per question (large files)")
    args = ap.parse_args()

    questions = load_suite(args.suite)
    sid = init_session()
    print(f"session={sid}  suite={args.suite}  n={len(questions)}", file=sys.stderr)

    results = []
    t_start = time.time()
    for i, item in enumerate(questions, 1):
        expected = set(item["expected"])
        t0 = time.time()
        sid, err, text = call_search(sid, item["q"], args.topk)
        latency_ms = int((time.time() - t0) * 1000)
        if err is not None:
            print(f"[{i:03d}] ERROR: {err}", file=sys.stderr)
            results.append({"i": i, "suite": item["suite"], "q": item["q"],
                            "expected": sorted(expected), "topic": item["topic"],
                            "kind": item.get("kind", "lookup"), "specs": [],
                            "error": str(err), "top1": False, "top5": False})
            continue
        specs = extract_specs(text)
        is_top1 = bool(specs) and specs[0] in expected
        is_top5 = any(s in expected for s in specs[:5])
        rec = {
            "i": i, "suite": item["suite"], "q": item["q"],
            "expected": sorted(expected), "topic": item["topic"],
            "kind": item.get("kind", "lookup"),
            "specs": specs, "top1": is_top1, "top5": is_top5,
            "latency_ms": latency_ms,
            **extract_signals(text),
        }
        if args.keep_raw:
            rec["raw"] = text
        results.append(rec)
        marker = "OK1" if is_top1 else ("OK5" if is_top5 else "MISS")
        print(f"[{i:03d}] {marker:4s} {item['suite']:5s} exp={'/'.join(sorted(expected)):24s} "
              f"got={','.join(specs[:5]):40s} {item['q']}")
        time.sleep(0.03)

    summary = summarize(results)
    summary["wall_seconds"] = round(time.time() - t_start, 1)
    lat = sorted(r["latency_ms"] for r in results if "latency_ms" in r)
    if lat:
        summary["latency_ms"] = {
            "p50": lat[len(lat) // 2],
            "p95": lat[min(len(lat) - 1, int(len(lat) * 0.95))],
            "max": lat[-1],
        }

    print()
    print(f"TOP1: {summary['top1']}/{summary['total']} = {summary['top1_pct']}%")
    print(f"TOP5: {summary['top5']}/{summary['total']} = {summary['top5_pct']}%")
    for name, store in (("suite", summary["by_suite"]),
                        ("topic", summary["by_topic"]),
                        ("kind", summary["by_kind"])):
        print(f"\nBy {name}:")
        for key, d in sorted(store.items()):
            print(f"  {key:10s} n={d['n']:3d}  top1={d['top1']:3d}  top5={d['top5']:3d}")

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w") as f:
        json.dump({"label": args.label, "suite": args.suite, "topk": args.topk,
                   "summary": summary, "results": results}, f, indent=2)
    print(f"\nwrote {out_path}")


if __name__ == "__main__":
    main()

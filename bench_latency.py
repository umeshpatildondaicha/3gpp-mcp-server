#!/usr/bin/env python3
"""
Latency benchmark for the search3gpp tool.

Issues the 20-question telecom set against verbosity=brief/normal/full and
reports per-call latency (server-side, measured around the HTTP request)
plus per-verbosity p50/p95/p99. Also prints absolute counts so cold-start
outliers are visible.

Usage: ensure the MCP server is up on http://localhost:3000/mcp, then
       python3 bench_latency.py
"""
import json, os, sys, time, urllib.request
from statistics import median

URL = os.environ.get("MCP_URL", "http://localhost:3000/mcp")
HEADERS_BASE = {
    "Content-Type": "application/json",
    "Accept": "application/json, text/event-stream",
}

QUESTIONS = [
    "How does initial access work in 5G NR?",
    "What is the SSB structure in NR?",
    "RACH preamble formats in 5G NR",
    "PDCP duplication in NR",
    "BWP switching procedure in 5G",
    "X.733 alarm reporting model in 3GPP",
    "PRB usage measurement in 5G NR",
    "NRCellDU information object class attributes",
    "5G end-to-end KPI definition",
    "Network slice subnet lifecycle management",
    "What does the AMF do in 5G core?",
    "PDU session establishment procedure",
    "SMF interactions with UPF over N4",
    "5G AKA authentication procedure",
    "Key derivation hierarchy in 5G NAS",
    "BGP-4 path attribute types",
    "BFD asynchronous mode operation",
    "ITU-T G.8032 ring protection switching",
    "ETSI NFV MANO lifecycle operations",
    "O-RAN fronthaul WG4 control plane",
]
VERBOSITIES = ["brief", "normal", "full"]


def parse_sse(text):
    payload = [line[5:].lstrip() for line in text.splitlines() if line.startswith("data:")]
    return json.loads("\n".join(payload)) if payload else json.loads(text)


def post(session_id, body):
    headers = dict(HEADERS_BASE)
    if session_id:
        headers["Mcp-Session-Id"] = session_id
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=120) as resp:
        sid = resp.headers.get("Mcp-Session-Id") or session_id
        body_text = resp.read().decode("utf-8", errors="replace")
        if "text/event-stream" in resp.headers.get("Content-Type", ""):
            return sid, parse_sse(body_text)
        return sid, json.loads(body_text) if body_text.strip() else None


def init_session():
    sid, _ = post(None, {
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                    "clientInfo": {"name": "latency-bench", "version": "1"}},
    })
    post(sid, {"jsonrpc": "2.0", "method": "notifications/initialized"})
    return sid


def call_search(sid, query, verbosity, k=5):
    args = {"query": query, "topK": k}
    if verbosity:
        args["verbosity"] = verbosity
    return post(sid, {
        "jsonrpc": "2.0", "id": 2, "method": "tools/call",
        "params": {"name": "search3gpp", "arguments": args},
    })


def percentile(values, pct):
    if not values:
        return 0
    s = sorted(values)
    k = (len(s) - 1) * (pct / 100.0)
    f = int(k)
    c = min(f + 1, len(s) - 1)
    return s[f] + (s[c] - s[f]) * (k - f)


def main():
    sid = init_session()
    # warm-up — one query to make sure caches are loaded.
    call_search(sid, QUESTIONS[0], "normal")

    latencies = {v: [] for v in VERBOSITIES}
    for i, q in enumerate(QUESTIONS, 1):
        for v in VERBOSITIES:
            t0 = time.perf_counter()
            try:
                call_search(sid, q, v)
                elapsed_ms = (time.perf_counter() - t0) * 1000
            except Exception as e:
                print(f"[{i:02d}] {v}: ERROR {e}", file=sys.stderr)
                continue
            latencies[v].append(elapsed_ms)

    print()
    print(f"{'verbosity':>10} {'n':>4} {'p50':>8} {'p95':>8} {'p99':>8} {'max':>8} {'mean':>8}")
    print("-" * 65)
    for v in VERBOSITIES:
        L = latencies[v]
        if not L:
            print(f"{v:>10}: no successful calls")
            continue
        print(f"{v:>10} {len(L):>4d} "
              f"{median(L):>7.0f}ms "
              f"{percentile(L, 95):>7.0f}ms "
              f"{percentile(L, 99):>7.0f}ms "
              f"{max(L):>7.0f}ms "
              f"{sum(L)/len(L):>7.0f}ms")

    out = {"latencies_ms": latencies, "questions": QUESTIONS, "verbosities": VERBOSITIES}
    with open("bench_latency_results.json", "w") as f:
        json.dump(out, f, indent=2)


if __name__ == "__main__":
    main()

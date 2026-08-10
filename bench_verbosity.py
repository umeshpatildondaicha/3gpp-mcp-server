#!/usr/bin/env python3
"""
20-question telecom benchmark for the new adaptive sentence-selection
(verbosity) feature on search3gpp.

For each question, calls the MCP server three times — verbosity=brief,
normal, full — and compares:
  - response character / sentence count
  - same spec_id ordering (i.e. compression didn't reshuffle retrieval)
  - presence of the "More :" drill-down hint
  - qualitative sample of the first hit body

Prints a per-question table and an aggregate summary.
Writes detailed output to bench_verbosity_results.json.
"""
import json, os, re, sys, time, urllib.request

URL = os.environ.get("MCP_URL", "http://localhost:3000/mcp")
HEADERS_BASE = {
    "Content-Type": "application/json",
    "Accept": "application/json, text/event-stream",
}

QUESTIONS = [
    # 5G NR radio / RRC / NAS
    "How does initial access work in 5G NR?",
    "What is the SSB structure in NR?",
    "RACH preamble formats in 5G NR",
    "PDCP duplication in NR",
    "BWP switching procedure in 5G",
    # OAM / PM / FM / CM
    "X.733 alarm reporting model in 3GPP",
    "PRB usage measurement in 5G NR",
    "NRCellDU information object class attributes",
    "5G end-to-end KPI definition",
    "Network slice subnet lifecycle management",
    # Core / NAS / SBA
    "What does the AMF do in 5G core?",
    "PDU session establishment procedure",
    "SMF interactions with UPF over N4",
    # Security
    "5G AKA authentication procedure",
    "Key derivation hierarchy in 5G NAS",
    # Transport / IP / optical
    "BGP-4 path attribute types",
    "BFD asynchronous mode operation",
    "ITU-T G.8032 ring protection switching",
    # NFV / O-RAN
    "ETSI NFV MANO lifecycle operations",
    "O-RAN fronthaul WG4 control plane",
]
assert len(QUESTIONS) == 20

VERBOSITIES = ["brief", "normal", "full"]

# ── MCP plumbing (mirrors benchmark_oam.py) ───────────────────────────────────

def parse_sse(text):
    payload = []
    for line in text.splitlines():
        if line.startswith("data:"):
            payload.append(line[5:].lstrip())
    if not payload:
        return json.loads(text)
    return json.loads("\n".join(payload))


def post(session_id, body):
    headers = dict(HEADERS_BASE)
    if session_id:
        headers["Mcp-Session-Id"] = session_id
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=120) as resp:
        sid = resp.headers.get("Mcp-Session-Id") or session_id
        body_text = resp.read().decode("utf-8", errors="replace")
        ctype = resp.headers.get("Content-Type", "")
        if "text/event-stream" in ctype:
            return sid, parse_sse(body_text)
        if not body_text.strip():
            return sid, None
        return sid, json.loads(body_text)


def init_session():
    sid, _ = post(None, {
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "bench-verbosity", "version": "1"},
        },
    })
    post(sid, {"jsonrpc": "2.0", "method": "notifications/initialized"})
    return sid


def call_search(sid, query, verbosity, k=5):
    args = {"query": query, "topK": k}
    if verbosity:
        args["verbosity"] = verbosity
    sid2, resp = post(sid, {
        "jsonrpc": "2.0", "id": 2, "method": "tools/call",
        "params": {"name": "search3gpp", "arguments": args},
    })
    if resp.get("error"):
        return sid2, resp["error"], None
    content = resp["result"]["content"][0]["text"]
    # Spring AI double-encodes the tool output as a JSON string.
    if content.startswith('"') and content.endswith('"'):
        try:
            content = json.loads(content)
        except json.JSONDecodeError:
            pass
    return sid2, None, content


# ── parsing helpers ───────────────────────────────────────────────────────────

SPEC_RE = re.compile(r"^\[(\d+)\]\s+([^\s|]+)\s+\|", re.M)
BODY_RE = re.compile(r"^\s{4}(Key|Excerpt)\s*:\s*(.+)$", re.M)
DRILL_RE = re.compile(r"^\s{4}More\s*:", re.M)


def extract_specs(text):
    return [m.group(2) for m in SPEC_RE.finditer(text)]


def first_body(text):
    m = BODY_RE.search(text)
    return m.group(2).strip() if m else ""


def sentence_count(body):
    if not body:
        return 0
    # Cheap split — count terminal . ! ? not preceded by common abbrev.
    parts = re.split(r"(?<=[.!?])\s+(?=[A-Z0-9])", body.strip())
    return sum(1 for p in parts if len(p.strip()) >= 4)


# ── main ──────────────────────────────────────────────────────────────────────

def main():
    sid = init_session()
    print(f"session: {sid}", file=sys.stderr)

    results = []
    agg = {v: {"chars": 0, "sents": 0, "drill": 0} for v in VERBOSITIES}
    stable_ordering = 0

    print()
    print(f"{'#':>2} {'brief':>6} {'normal':>7} {'full':>7} {'stable':>7}  question")
    print("-" * 100)

    for i, q in enumerate(QUESTIONS, 1):
        row = {"q": q, "by_verbosity": {}}
        specs_per = {}
        for v in VERBOSITIES:
            sid, err, text = call_search(sid, q, v, 5)
            if err is not None:
                row["by_verbosity"][v] = {"error": str(err)}
                specs_per[v] = []
                continue
            specs = extract_specs(text)
            body = first_body(text)
            n_sent = sentence_count(body)
            drill = bool(DRILL_RE.search(text))
            row["by_verbosity"][v] = {
                "chars": len(text),
                "body_chars": len(body),
                "first_body_sentences": n_sent,
                "drill_hint": drill,
                "specs": specs,
                "first_body": body,
                "raw": text,
            }
            agg[v]["chars"] += len(text)
            agg[v]["sents"] += n_sent
            agg[v]["drill"] += int(drill)
            specs_per[v] = specs
            time.sleep(0.02)

        # Retrieval stability: did brief/normal preserve full's spec_id order?
        stable = (specs_per.get("brief") == specs_per.get("full")
                  and specs_per.get("normal") == specs_per.get("full")
                  and bool(specs_per.get("full")))
        stable_ordering += int(stable)
        row["stable_ordering"] = stable

        b = row["by_verbosity"]["brief"].get("chars", 0)
        n = row["by_verbosity"]["normal"].get("chars", 0)
        f = row["by_verbosity"]["full"].get("chars", 0)
        mark = "yes" if stable else "no"
        print(f"{i:>2} {b:>6} {n:>7} {f:>7} {mark:>7}  {q}")

        results.append(row)

    total = len(QUESTIONS)
    print()
    print("AGGREGATE")
    print("-" * 60)
    full_chars = agg["full"]["chars"] or 1
    for v in VERBOSITIES:
        a = agg[v]
        ratio = a["chars"] / full_chars
        print(f"  {v:6s}  total={a['chars']:>7} chars  avg_sentences/hit={a['sents']/total:.2f}  "
              f"drill_hint={a['drill']}/{total}  ratio_vs_full={ratio:.0%}")
    print()
    brief_savings = 1 - agg["brief"]["chars"] / full_chars
    normal_savings = 1 - agg["normal"]["chars"] / full_chars
    print(f"  brief  saved {brief_savings:.0%} characters vs full")
    print(f"  normal saved {normal_savings:.0%} characters vs full")
    print(f"  retrieval order preserved on {stable_ordering}/{total} questions")

    # Sample outputs — 3 questions, normal vs full first-hit body
    print()
    print("SAMPLE OUTPUTS (first-hit body, normal vs full)")
    print("=" * 80)
    for idx in (0, 5, 13):
        r = results[idx]
        print(f"\nQ{idx+1}: {r['q']}")
        for v in ("normal", "full"):
            d = r["by_verbosity"][v]
            body = d.get("first_body", "")
            shown = body if len(body) <= 500 else body[:500] + " […truncated for display]"
            print(f"  [{v}] {d.get('body_chars', 0)} chars  →  {shown}")

    out_path = "bench_verbosity_results.json"
    with open(out_path, "w") as fp:
        json.dump({
            "questions": QUESTIONS,
            "results": results,
            "aggregate": agg,
            "stable_ordering": stable_ordering,
            "total": total,
        }, fp, indent=2)
    print(f"\nwrote {out_path}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
50-question OAM (PM/FM/CM/topology) retrieval benchmark for the 3gpp-mcp-server.

Drives the running server on http://localhost:3000/mcp via MCP streamable HTTP.
For each question:
  - calls search3gpp with topK=5
  - extracts the spec_ids returned in the top-5
  - scores TOP1 and TOP5 hit against an `expected` set (any-of)
Writes detailed results to bench_oam_results.json and prints a summary.
"""
import json, os, re, sys, time, urllib.request

URL = os.environ.get("MCP_URL", "http://localhost:3000/mcp")
HEADERS_BASE = {
    "Content-Type": "application/json",
    "Accept": "application/json, text/event-stream",
}

# expected = set of spec_ids that count as correct (any-of match in top1/top5)
# topic categorises the question. note: 28.552 covers most 5G PM measurements; 28.554 for KPIs.
QUESTIONS = [
    # ---- PM: 5G performance measurements (28.552) ----
    {"q": "PRB usage measurement in 5G NR",                      "expected": {"28.552"}, "topic": "PM"},
    {"q": "PDCP SDU data volume measurement gNB",                 "expected": {"28.552"}, "topic": "PM"},
    {"q": "RACH preamble received measurement",                   "expected": {"28.552"}, "topic": "PM"},
    {"q": "average DL UE throughput in NR cell",                  "expected": {"28.552", "28.554"}, "topic": "PM"},
    {"q": "RRC connection establishment success rate",            "expected": {"28.552", "28.554"}, "topic": "PM"},
    {"q": "transmit power utilization at gNB",                    "expected": {"28.552"}, "topic": "PM"},
    {"q": "paging records discarded gNB-CU",                      "expected": {"28.552"}, "topic": "PM"},
    {"q": "AMFFunction registered subscribers measurement",        "expected": {"28.552", "28.541"}, "topic": "PM"},
    {"q": "SMFFunction PDU session establishment counter",         "expected": {"28.552"}, "topic": "PM"},
    {"q": "UPFFunction GTP data volume measurement",               "expected": {"28.552"}, "topic": "PM"},
    {"q": "SDM PUSCH PRB usage measurement",                       "expected": {"28.552"}, "topic": "PM"},
    {"q": "5G NR measurement type naming convention",              "expected": {"28.552"}, "topic": "PM"},
    # ---- PM: KPIs (28.554) ----
    {"q": "5G end to end key performance indicator definition",    "expected": {"28.554"}, "topic": "PM"},
    {"q": "accessibility KPI calculation 5G",                      "expected": {"28.554"}, "topic": "PM"},
    {"q": "cell availability KPI 5G",                              "expected": {"28.554", "32.425"}, "topic": "PM"},
    {"q": "energy efficiency KPI for 5G network",                  "expected": {"28.554"}, "topic": "PM"},
    {"q": "service experience KPI mobile network",                 "expected": {"28.554"}, "topic": "PM"},
    # ---- PM: trace + LTE (32.421/32.425) ----
    {"q": "subscriber trace job activation",                       "expected": {"32.421", "32.422"}, "topic": "PM"},
    {"q": "LTE eNodeB PRB usage trace measurement",                 "expected": {"32.425"}, "topic": "PM"},
    {"q": "trace measurements collection LTE",                     "expected": {"32.425", "32.421"}, "topic": "PM"},
    # ---- FM: alarms (32.111-2) ----
    {"q": "X.733 alarm reporting model 3GPP",                       "expected": {"32.111-2"}, "topic": "FM"},
    {"q": "alarm IRP information service",                          "expected": {"32.111-2"}, "topic": "FM"},
    {"q": "probable cause field alarm record",                      "expected": {"32.111-2"}, "topic": "FM"},
    {"q": "alarm severity values critical major minor",             "expected": {"32.111-2"}, "topic": "FM"},
    {"q": "notify new alarm operation MnS",                         "expected": {"32.111-2", "28.532"}, "topic": "FM"},
    {"q": "clear alarm procedure cleared by operator",              "expected": {"32.111-2"}, "topic": "FM"},
    {"q": "alarm acknowledgement state ack flag",                   "expected": {"32.111-2"}, "topic": "FM"},
    {"q": "specific problem field alarm",                           "expected": {"32.111-2"}, "topic": "FM"},
    {"q": "fault management heartbeat alarm 3GPP",                  "expected": {"32.111-2"}, "topic": "FM"},
    {"q": "alarm correlation root cause analysis OAM",              "expected": {"32.111-2"}, "topic": "FM"},
    # ---- CM: 5G NRM (28.541) ----
    {"q": "NRCellDU information object class attributes",           "expected": {"28.541"}, "topic": "CM"},
    {"q": "NRCellCU configuration attributes 5G",                   "expected": {"28.541"}, "topic": "CM"},
    {"q": "GNBDUFunction managed object class definition",          "expected": {"28.541"}, "topic": "CM"},
    {"q": "AMFFunction NRM IOC attributes",                         "expected": {"28.541"}, "topic": "CM"},
    {"q": "SMFFunction NRM information object",                     "expected": {"28.541"}, "topic": "CM"},
    {"q": "UPFFunction managed object NRM",                         "expected": {"28.541"}, "topic": "CM"},
    {"q": "PLMNInfo data type 5G NRM",                              "expected": {"28.541"}, "topic": "CM"},
    # ---- CM: provisioning + generic NRM ----
    {"q": "provisioning MnS create managed object operation",        "expected": {"28.532"}, "topic": "CM"},
    {"q": "generic network resource model integration reference point","expected": {"28.622", "28.623"}, "topic": "CM"},
    {"q": "ManagedElement IOC inheritance",                          "expected": {"28.622", "28.541"}, "topic": "CM"},
    {"q": "SubNetwork managed object hierarchy",                     "expected": {"28.622", "28.541"}, "topic": "CM"},
    {"q": "ManagedFunction abstract class NRM",                      "expected": {"28.622"}, "topic": "CM"},
    {"q": "vendor-specific extension generic NRM",                   "expected": {"28.622", "28.623"}, "topic": "CM"},
    {"q": "configuration management bulk CM file format",            "expected": {"32.600", "28.532"}, "topic": "CM"},
    # ---- Topology / network slicing ----
    {"q": "network slice subnet management lifecycle",               "expected": {"28.530", "28.541"}, "topic": "TOPO"},
    {"q": "NetworkSlice IOC attributes",                             "expected": {"28.541"}, "topic": "TOPO"},
    {"q": "service profile network slice attributes",                "expected": {"28.541", "28.530"}, "topic": "TOPO"},
    {"q": "network slice instance lifecycle phases",                 "expected": {"28.530"}, "topic": "TOPO"},
    {"q": "NSSI provisioning workflow",                              "expected": {"28.545", "28.530", "28.541"}, "topic": "TOPO"},
    # ---- Performance assurance / file format ----
    {"q": "performance assurance management orchestration",          "expected": {"28.550"}, "topic": "PM"},
]

assert len(QUESTIONS) == 50, f"need 50, got {len(QUESTIONS)}"


def parse_sse(text: str):
    """Parse SSE response and return JSON of the first 'message' event."""
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
    sid, resp = post(None, {
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "bench-oam", "version": "1"},
        },
    })
    # required notification
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
    # Spring AI double-encodes the tool string output: the text field is itself a
    # JSON-quoted string. Decode once so regex sees real newlines.
    if content.startswith('"') and content.endswith('"'):
        try:
            content = json.loads(content)
        except json.JSONDecodeError:
            pass
    return sid2, None, content


SPEC_RE = re.compile(r"^\[(\d+)\]\s+([^\s|]+)\s+\|", re.M)


def extract_specs(text):
    return [m.group(2) for m in SPEC_RE.finditer(text)]


def main():
    sid = init_session()
    print(f"session id: {sid}", file=sys.stderr)

    results = []
    top1 = top5 = 0
    by_topic = {}
    for i, item in enumerate(QUESTIONS, 1):
        q = item["q"]; expected = set(item["expected"]); topic = item["topic"]
        sid, err, text = call_search(sid, q, 5)
        if err is not None:
            print(f"[{i:02d}] ERROR: {err}", file=sys.stderr)
            results.append({"i": i, "q": q, "expected": list(expected), "topic": topic, "specs": [], "error": str(err)})
            continue
        specs = extract_specs(text)
        is_top1 = bool(specs) and specs[0] in expected
        is_top5 = any(s in expected for s in specs[:5])
        top1 += int(is_top1); top5 += int(is_top5)
        d = by_topic.setdefault(topic, {"n": 0, "top1": 0, "top5": 0})
        d["n"] += 1; d["top1"] += int(is_top1); d["top5"] += int(is_top5)
        marker = "OK1" if is_top1 else ("OK5" if is_top5 else "MISS")
        print(f"[{i:02d}] {marker:4s} | exp={'/'.join(sorted(expected)):20s} | got={','.join(specs[:5])} | {q}")
        results.append({"i": i, "q": q, "expected": list(expected), "topic": topic, "specs": specs, "raw": text, "top1": is_top1, "top5": is_top5})
        time.sleep(0.05)

    print()
    print(f"TOP1: {top1}/{len(QUESTIONS)} = {top1/len(QUESTIONS):.0%}")
    print(f"TOP5: {top5}/{len(QUESTIONS)} = {top5/len(QUESTIONS):.0%}")
    print()
    print("By topic:")
    for t, d in sorted(by_topic.items()):
        print(f"  {t:5s} n={d['n']:2d}  top1={d['top1']:2d}/{d['n']}  top5={d['top5']:2d}/{d['n']}")

    out = {
        "summary": {"top1": top1, "top5": top5, "total": len(QUESTIONS), "by_topic": by_topic},
        "results": results,
    }
    with open("bench_oam_results.json", "w") as f:
        json.dump(out, f, indent=2)
    print("\nwrote bench_oam_results.json")


if __name__ == "__main__":
    main()

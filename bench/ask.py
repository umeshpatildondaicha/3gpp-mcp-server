#!/usr/bin/env python3
"""
Ask the running server a single question — the debugging counterpart to run_bench.py.

    python3 bench/ask.py "allowed range for siPeriodicity"
    python3 bench/ask.py -k 10 "LTE MAC random access"
    python3 bench/ask.py --series 36 "RRC measurement report configuration"
    python3 bench/ask.py --raw "PRB usage measurement in 5G NR"

Prints the confidence header and the ranked spec list. --raw dumps the server's
full response instead, including the extracted snippets.

Reads MCP_URL (default http://localhost:3000/mcp), same as run_bench.py.
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from run_bench import init_session, post, extract_signals, extract_specs  # noqa: E402


def call(sid, query, k, series, release, doc_type, verbosity):
    args = {"query": query, "topK": k}
    if series:
        args["series"] = series
    if release:
        args["release"] = release
    if doc_type:
        args["docType"] = doc_type
    if verbosity:
        args["verbosity"] = verbosity
    sid, resp = post(sid, {
        "jsonrpc": "2.0", "id": 2, "method": "tools/call",
        "params": {"name": "search3gpp", "arguments": args},
    })
    if resp.get("error"):
        raise SystemExit(f"server error: {resp['error']}")
    text = resp["result"]["content"][0]["text"]
    if text.startswith('"') and text.endswith('"'):
        import json
        try:
            text = json.loads(text)
        except json.JSONDecodeError:
            pass
    return text


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("query")
    p.add_argument("-k", "--top-k", type=int, default=5)
    p.add_argument("--series")
    p.add_argument("--release")
    p.add_argument("--doc-type")
    p.add_argument("--verbosity", choices=["brief", "normal", "full"])
    p.add_argument("--raw", action="store_true", help="print the full server response")
    a = p.parse_args()

    text = call(init_session(), a.query, a.top_k, a.series, a.release, a.doc_type, a.verbosity)

    if a.raw:
        print(text)
        return

    sig = extract_signals(text)
    print(f"query      : {a.query}")
    print(f"confidence : {sig['confidence']}  margin={sig['margin']}  top={sig['top_score']}")
    for line in text.splitlines():
        if line.startswith("Intent:") or line.startswith("Confidence:"):
            print(line)
    print("ranked specs:")
    for i, spec in enumerate(extract_specs(text), 1):
        print(f"  {i}. {spec}")


if __name__ == "__main__":
    main()

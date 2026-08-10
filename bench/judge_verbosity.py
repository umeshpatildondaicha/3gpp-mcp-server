#!/usr/bin/env python3
"""
LLM-judge step for the 20-question telecom benchmark.

Loads bench_verbosity_results.json (produced by bench_verbosity.py) and, for
each question, asks Claude to compare the BRIEF and NORMAL responses against
the FULL response. The judge is asked to identify:

  1. Answer-relevant facts present in FULL but missing from BRIEF / NORMAL.
  2. A categorical verdict per compressed level:
       - equivalent   : no answer-relevant content lost
       - minor_loss   : peripheral context dropped, core answer still derivable
       - major_loss   : a fact a consumer would need is missing
  3. Any hallucination — statements in BRIEF/NORMAL that aren't in FULL.
     (Should be 0/20 because compression is extractive, but worth verifying.)

Output:
  - bench_judge_results.json  (per-question verdicts + omissions list)
  - stdout summary table

Cost: 20 single-shot calls, ~80K input + ~20K output tokens with Sonnet 4.6
       ≈ $0.30. Set JUDGE_MODEL to override.
"""
import json, os, re, sys, time
from anthropic import Anthropic, APIError

MODEL    = os.environ.get("JUDGE_MODEL", "claude-sonnet-4-6")
INPUT_F  = os.environ.get("JUDGE_INPUT", "bench_verbosity_results.json")
OUTPUT_F = os.environ.get("JUDGE_OUTPUT", "bench_judge_results.json")

SYSTEM_PROMPT = """You are an expert telecom-standards reviewer evaluating
retrieval-augmented-generation outputs against the 3GPP knowledge base.

Your task: given a user question and three versions of the same retrieved
context (BRIEF, NORMAL, FULL), determine whether the compressed versions
(BRIEF, NORMAL) lose any *answer-relevant* facts that the FULL version
contains.

Important rules:
- Only count omissions that are answer-relevant to the specific question
  asked. Reference-list boilerplate, abbreviations, copyright notices, or
  section numbers do NOT count as omissions.
- A "fact" is a statement a downstream consumer would need to correctly
  answer the question. Be strict: list only material omissions.
- Hallucinations are statements in BRIEF/NORMAL not supported by FULL.
  Since compression is extractive (sentence-level selection from the same
  text), hallucinations should be 0. Flag any you find.

Respond with valid JSON ONLY in this exact schema (no markdown, no prose):
{
  "brief":  {"verdict": "equivalent|minor_loss|major_loss",
             "missing_facts": ["...", "..."],
             "hallucinations": ["..."]},
  "normal": {"verdict": "equivalent|minor_loss|major_loss",
             "missing_facts": ["...", "..."],
             "hallucinations": ["..."]},
  "notes": "one short sentence on overall quality"
}
"""


def build_user_msg(q, brief, normal, full):
    return f"""QUESTION:
{q}

BRIEF context (~3 key sentences, drill-down available):
{brief}

NORMAL context (adaptive sentence selection with neighbor expansion):
{normal}

FULL context (raw retrieved chunks):
{full}

Evaluate BRIEF and NORMAL against FULL per the rubric. Respond with the JSON schema only."""


def parse_json_strict(text):
    """Extract the first {...} block and JSON-parse it. Models occasionally
    wrap JSON in markdown fences even when told not to."""
    fence = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.S)
    if fence:
        return json.loads(fence.group(1))
    obj = re.search(r"\{.*\}", text, re.S)
    if obj:
        return json.loads(obj.group(0))
    raise ValueError(f"no JSON object found in:\n{text[:400]}")


def main():
    if not os.environ.get("ANTHROPIC_API_KEY"):
        print("ERROR: ANTHROPIC_API_KEY not set in environment.", file=sys.stderr)
        sys.exit(2)

    with open(INPUT_F) as f:
        bench = json.load(f)

    client = Anthropic()
    out = []

    counts = {
        "brief":  {"equivalent": 0, "minor_loss": 0, "major_loss": 0, "hallucinations": 0},
        "normal": {"equivalent": 0, "minor_loss": 0, "major_loss": 0, "hallucinations": 0},
    }

    print(f"judge model: {MODEL}", file=sys.stderr)
    print(f"{'#':>2} {'brief':>11} {'normal':>11}  question")
    print("-" * 90)

    for i, row in enumerate(bench["results"], 1):
        q = row["q"]
        brief  = row["by_verbosity"]["brief"]["raw"]
        normal = row["by_verbosity"]["normal"]["raw"]
        full   = row["by_verbosity"]["full"]["raw"]

        user_msg = build_user_msg(q, brief, normal, full)
        t0 = time.time()
        try:
            resp = client.messages.create(
                model=MODEL,
                max_tokens=1500,
                # Prompt-cache the system block — same across all 20 calls
                system=[{
                    "type": "text",
                    "text": SYSTEM_PROMPT,
                    "cache_control": {"type": "ephemeral"},
                }],
                messages=[{"role": "user", "content": user_msg}],
            )
        except APIError as e:
            print(f"[{i:02d}] API error: {e}", file=sys.stderr)
            out.append({"i": i, "q": q, "error": str(e)})
            continue

        raw_text = "".join(b.text for b in resp.content if b.type == "text")
        try:
            verdict = parse_json_strict(raw_text)
        except (ValueError, json.JSONDecodeError) as e:
            print(f"[{i:02d}] parse error: {e}", file=sys.stderr)
            out.append({"i": i, "q": q, "raw": raw_text, "parse_error": str(e)})
            continue

        for level in ("brief", "normal"):
            v = verdict.get(level, {})
            cat = v.get("verdict", "?")
            if cat in counts[level]:
                counts[level][cat] += 1
            if v.get("hallucinations"):
                counts[level]["hallucinations"] += 1

        b_cat = verdict.get("brief",  {}).get("verdict", "?")
        n_cat = verdict.get("normal", {}).get("verdict", "?")
        print(f"{i:>2} {b_cat:>11} {n_cat:>11}  {q}")

        out.append({
            "i": i, "q": q,
            "verdict": verdict,
            "latency_ms": int((time.time() - t0) * 1000),
            "input_tokens":           resp.usage.input_tokens,
            "output_tokens":          resp.usage.output_tokens,
            "cache_creation_tokens":  getattr(resp.usage, "cache_creation_input_tokens", 0),
            "cache_read_tokens":      getattr(resp.usage, "cache_read_input_tokens", 0),
        })

    n = len(bench["results"])
    print()
    print(f"{'level':>8} {'equiv':>7} {'minor':>7} {'major':>7} {'halluc':>7}")
    print("-" * 50)
    for level in ("brief", "normal"):
        c = counts[level]
        print(f"{level:>8} {c['equivalent']:>7} {c['minor_loss']:>7} "
              f"{c['major_loss']:>7} {c['hallucinations']:>7}")

    # Show the missing-fact lists for any major losses
    print()
    print("MAJOR LOSSES (per question)")
    print("-" * 70)
    any_major = False
    for r in out:
        if "verdict" not in r:
            continue
        for level in ("brief", "normal"):
            v = r["verdict"].get(level, {})
            if v.get("verdict") == "major_loss":
                any_major = True
                print(f"\nQ{r['i']} [{level}] {r['q']}")
                for fact in v.get("missing_facts", []):
                    print(f"  - {fact}")
    if not any_major:
        print("  (none — every compressed version preserved the answer-relevant facts)")

    # Token usage / cost
    total_in   = sum(r.get("input_tokens", 0)        for r in out if "verdict" in r)
    total_out  = sum(r.get("output_tokens", 0)       for r in out if "verdict" in r)
    total_cc   = sum(r.get("cache_creation_tokens", 0) for r in out if "verdict" in r)
    total_cr   = sum(r.get("cache_read_tokens", 0)     for r in out if "verdict" in r)
    print()
    print(f"tokens — input={total_in}  output={total_out}  "
          f"cache_creation={total_cc}  cache_read={total_cr}")

    with open(OUTPUT_F, "w") as f:
        json.dump({"model": MODEL, "counts": counts, "results": out}, f, indent=2)
    print(f"\nwrote {OUTPUT_F}")


if __name__ == "__main__":
    main()

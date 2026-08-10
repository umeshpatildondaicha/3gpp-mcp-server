#!/usr/bin/env python3
"""
Local test UI for the 3GPP MCP server.

A zero-dependency (stdlib only) HTTP server that:
  1. serves ui/index.html on http://localhost:8080
  2. proxies /api/search to the MCP server's search3gpp tool
  3. optionally sends the retrieved chunks to Grok (xAI) to synthesize a
     grounded, citation-bound answer  →  /api/ask

The Grok key never leaves this machine: either set XAI_API_KEY in the
environment before starting, or paste it in the UI (it is kept in the
browser's localStorage and posted to this local proxy only).

Usage:
    export XAI_API_KEY=xai-...            # optional; can also be typed in the UI
    python3 ui/server.py                  # http://localhost:8080
    python3 ui/server.py --port 8090 --mcp http://localhost:3000/mcp
"""
import argparse
import itertools
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent

MCP_URL = os.environ.get("MCP_URL", "http://localhost:3000/mcp")

# ── Wire log ─────────────────────────────────────────────────────────────────
# Records both hops of every UI request, so you can see exactly what the browser
# sent and exactly what this proxy forwarded to the MCP server:
#
#   {"dir":"ui<-browser", "rid":"...", "path":"/api/search", "body":{...}}
#   {"dir":"mcp->server", "rid":"...", "url":"...", "body":{jsonrpc envelope}}
#   {"dir":"mcp<-server", "rid":"...", "status":200, "ms":2089, "body":"..."}
#
# `rid` ties the three together. One JSON object per line:
#     tail -f logs/ui-mcp.log | jq -c 'select(.dir=="mcp->server") | .body.params'
#
# Lands next to the Java server's logs (logs/ is resolved from this file, not
# from the working directory, so it does not move when you start the UI from
# elsewhere). Disable with UI_WIRE_LOG=0; redirect with UI_WIRE_LOG_PATH.
WIRE_LOG_ENABLED = os.environ.get("UI_WIRE_LOG", "1") != "0"
WIRE_LOG_PATH = Path(os.environ.get(
    "UI_WIRE_LOG_PATH", str(HERE.parent / "logs" / "ui-mcp.log")))
# Responses carry the full chunk text and run to tens of KB; the request side is
# what you normally need. 0 = no truncation.
WIRE_LOG_MAX_RESPONSE = int(os.environ.get("UI_WIRE_LOG_MAX_RESPONSE", "2000"))

_wire_lock = threading.Lock()
_wire_local = threading.local()
_wire_counter = itertools.count(1)


def wire_rid():
    """Correlation id for the request being served on this thread."""
    return getattr(_wire_local, "rid", "-")


def wire_new_rid():
    _wire_local.rid = f"r{next(_wire_counter):06d}"
    return _wire_local.rid


def wire_log(direction, **fields):
    if not WIRE_LOG_ENABLED:
        return
    entry = {"ts": time.strftime("%Y-%m-%dT%H:%M:%S"), "dir": direction,
             "rid": wire_rid(), **fields}
    try:
        line = json.dumps(entry, ensure_ascii=False, default=str)
    except (TypeError, ValueError):
        line = json.dumps({"ts": entry["ts"], "dir": direction, "rid": wire_rid(),
                           "error": "unserialisable entry"})
    try:
        with _wire_lock:
            WIRE_LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
            with WIRE_LOG_PATH.open("a", encoding="utf-8") as fh:
                fh.write(line + "\n")
    except OSError:
        pass  # logging must never break a request


def _clip(text):
    if not isinstance(text, str) or WIRE_LOG_MAX_RESPONSE <= 0:
        return text
    if len(text) <= WIRE_LOG_MAX_RESPONSE:
        return text
    return text[:WIRE_LOG_MAX_RESPONSE] + f"…[+{len(text) - WIRE_LOG_MAX_RESPONSE} chars]"


# Never write the LLM key to disk, even though it only travels to this proxy.
_REDACT_KEYS = {"apiKey", "api_key", "authorization"}


def _redact(obj):
    if isinstance(obj, dict):
        return {k: ("***" if k in _REDACT_KEYS else _redact(v)) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_redact(v) for v in obj]
    return obj

# All three providers speak the OpenAI /chat/completions shape, so one client
# covers them; only the base URL and the default model differ. "Grok" (xAI) and
# "Groq" are different companies with near-identical names — the key prefix is
# the reliable discriminator, so we route on that rather than on what the user
# thinks they have.
PROVIDERS = {
    "xai":    {"url": "https://api.x.ai/v1/chat/completions",
               "model": "grok-4",             "prefix": "xai-"},
    "groq":   {"url": "https://api.groq.com/openai/v1/chat/completions",
               "model": "llama-3.3-70b-versatile", "prefix": "gsk_"},
    "openai": {"url": "https://api.openai.com/v1/chat/completions",
               "model": "gpt-4o",             "prefix": "sk-"},
}
DEFAULT_PROVIDER = os.environ.get("LLM_PROVIDER", "auto")
DEFAULT_MODEL = os.environ.get("LLM_MODEL", "")


def resolve_provider(name, api_key):
    """Pick the provider explicitly, else infer it from the key prefix."""
    if name and name != "auto" and name in PROVIDERS:
        return name
    for pname, cfg in PROVIDERS.items():
        if api_key.startswith(cfg["prefix"]):
            return pname
    return "xai"

_session_lock = threading.Lock()
_session_id = None


# ── MCP client ───────────────────────────────────────────────────────────────

def _parse_sse(text):
    payload = [ln[5:].lstrip() for ln in text.splitlines() if ln.startswith("data:")]
    return json.loads("\n".join(payload)) if payload else json.loads(text)


def _mcp_post(session_id, body, timeout=180):
    headers = {"Content-Type": "application/json",
               "Accept": "application/json, text/event-stream"}
    if session_id:
        headers["Mcp-Session-Id"] = session_id
    encoded = json.dumps(body).encode()
    # The exact bytes that go to the MCP server, logged before the send so a
    # failed/hung call still leaves a record of what was attempted.
    wire_log("mcp->server", url=MCP_URL, method=body.get("method"),
             session=session_id, bytes=len(encoded), body=body)
    req = urllib.request.Request(MCP_URL, data=encoded,
                                 headers=headers, method="POST")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            sid = resp.headers.get("Mcp-Session-Id") or session_id
            raw = resp.read().decode("utf-8", errors="replace")
            ctype = resp.headers.get("Content-Type", "")
            wire_log("mcp<-server", status=resp.status, ms=int((time.time() - t0) * 1000),
                     content_type=ctype, bytes=len(raw), body=_clip(raw))
            if "text/event-stream" in ctype:
                return sid, _parse_sse(raw)
            return sid, (json.loads(raw) if raw.strip() else None)
    except Exception as e:  # noqa: BLE001 — log then re-raise unchanged
        wire_log("mcp<-server", ms=int((time.time() - t0) * 1000),
                 error=f"{type(e).__name__}: {e}")
        raise


def _new_session():
    sid, _ = _mcp_post(None, {
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                   "clientInfo": {"name": "3gpp-ui", "version": "1"}},
    })
    _mcp_post(sid, {"jsonrpc": "2.0", "method": "notifications/initialized"})
    return sid


def mcp_call(tool, arguments):
    """Call an MCP tool, transparently re-establishing a dead session once."""
    global _session_id
    for attempt in (1, 2):
        with _session_lock:
            if _session_id is None:
                _session_id = _new_session()
            sid = _session_id
        try:
            sid2, resp = _mcp_post(sid, {
                "jsonrpc": "2.0", "id": 2, "method": "tools/call",
                "params": {"name": tool, "arguments": arguments},
            })
            with _session_lock:
                _session_id = sid2
            if resp.get("error"):
                raise RuntimeError(resp["error"])
            text = resp["result"]["content"][0]["text"]
            # Spring AI double-encodes string tool output.
            if text.startswith('"') and text.endswith('"'):
                try:
                    text = json.loads(text)
                except json.JSONDecodeError:
                    pass
            return text
        except (urllib.error.HTTPError, urllib.error.URLError, KeyError) as e:
            with _session_lock:
                _session_id = None
            if attempt == 2:
                raise RuntimeError(f"MCP call failed: {e}") from e
    raise RuntimeError("unreachable")


# ── MCP tool catalogue ───────────────────────────────────────────────────────
# The server is the authority on what tools exist. Nothing here enumerates them:
# add a @Tool to the Java service and it shows up in the UI and becomes callable
# by the model on the next refresh, with no change to this file.

# "tools" is what this deployment exposes (post-filter); "raw" is everything the
# server implements. Availability checks use raw — see tool_available().
_tools_cache = {"at": 0.0, "tools": None, "raw": None}
TOOLS_TTL = float(os.environ.get("UI_TOOLS_TTL", "300"))

# Which of the server's tools this deployment exposes.
#   MCP_TOOLS          allow-list, comma-separated. Empty = expose everything.
#   MCP_TOOLS_EXCLUDE  deny-list, applied after the allow-list.
#
# This exists because search3gpp and search3gppBatch carry the SAME description
# on purpose — they describe one index, and a model choosing between them should
# read the same capability either way. The cost is that in agent mode, where the
# model picks the tool, the only thing telling them apart is `query` vs `queries`.
# Exposing just the one a deployment needs removes that ambiguity outright:
#   MCP_TOOLS_EXCLUDE=search3gppBatch   # single-query deployments
#   MCP_TOOLS_EXCLUDE=search3gpp        # batch-only deployments
# Deterministic endpoints (/api/search, /api/cmaudit) are unaffected either way —
# they pick by request shape, not by asking a model.
TOOLS_ALLOW = [s.strip() for s in os.environ.get("MCP_TOOLS", "").split(",") if s.strip()]
TOOLS_DENY = [s.strip() for s in os.environ.get("MCP_TOOLS_EXCLUDE", "").split(",") if s.strip()]


def _apply_tool_config(tools):
    if TOOLS_ALLOW:
        tools = [t for t in tools if t.get("name") in TOOLS_ALLOW]
    if TOOLS_DENY:
        tools = [t for t in tools if t.get("name") not in TOOLS_DENY]
    return tools


def tool_available(name):
    """Does the SERVER implement this tool?

    Deliberately checks the unfiltered list. MCP_TOOLS_EXCLUDE governs what the
    MODEL may choose in agent mode; it is not meant to disable the UI's own
    deterministic endpoints, which already know which tool they want. The case
    this guards is a server that genuinely lacks the tool — an older build, or
    one whose search3gpp still carries `queries` instead of a separate tool.
    """
    mcp_tools()  # populate the cache
    return any(t.get("name") == name for t in (_tools_cache["raw"] or []))


def mcp_tools(force=False):
    """MCP tools/list → (tool list, error). Cached; the set rarely changes.

    The returned list is already filtered by MCP_TOOLS / MCP_TOOLS_EXCLUDE, so
    every caller — /api/tools, agent mode, availability checks — sees exactly the
    set this deployment is configured to use.
    """
    now = time.time()
    if not force and _tools_cache["tools"] is not None and now - _tools_cache["at"] < TOOLS_TTL:
        return _tools_cache["tools"], None
    global _session_id
    for attempt in (1, 2):
        with _session_lock:
            if _session_id is None:
                _session_id = _new_session()
            sid = _session_id
        try:
            sid2, resp = _mcp_post(sid, {"jsonrpc": "2.0", "id": 3,
                                         "method": "tools/list", "params": {}})
            with _session_lock:
                _session_id = sid2
            raw = (resp.get("result") or {}).get("tools") or []
            tools = _apply_tool_config(raw)
            _tools_cache.update({"at": now, "tools": tools, "raw": raw})
            return tools, None
        except (urllib.error.HTTPError, urllib.error.URLError, KeyError, AttributeError) as e:
            with _session_lock:
                _session_id = None
            if attempt == 2:
                return [], f"tools/list failed: {e}"
    return [], "tools/list failed"


def openai_tools(tools):
    """MCP tool descriptors → OpenAI function-calling schemas."""
    out = []
    for t in tools:
        schema = t.get("inputSchema") or {"type": "object", "properties": {}}
        out.append({"type": "function", "function": {
            "name": t.get("name"),
            # The full MCP description is long (search3gpp's runs to ~50 lines of
            # routing guidance). That guidance is exactly what makes the model
            # pick the right tool, so it is passed through rather than trimmed.
            "description": (t.get("description") or "").strip(),
            "parameters": schema,
        }})
    return out


# One tool result can be a full-verbosity chunk dump. Several of those in a
# transcript will exceed the context window, so each is capped; the model sees
# the cap and can ask for a narrower query instead of silently losing evidence.
TOOL_RESULT_MAX = int(os.environ.get("UI_TOOL_RESULT_MAX", "6000"))
AGENT_MAX_STEPS = int(os.environ.get("UI_AGENT_MAX_STEPS", "6"))


AGENT_PROMPT = """You answer telecom standards questions using the tools provided.

The tools are the ONLY source of fact you have. You have no reliable memory of
specification content, so do not answer from recall: call a tool, read what comes
back, and ground every statement in it.

HOW TO WORK
- Read each tool's own description before choosing. They state what they cover
  and when NOT to use them; that guidance is authoritative and current.
- Prefer the most specific tool. A parameter's permitted values come from the IE
  lookup, not from a text search that merely mentions the parameter.
- NEVER SEARCH A BARE PARAMETER NAME OUT OF A CONFIG PAYLOAD. An audit payload
  gives you `parameterName`, `moHierarchy` and usually `vendor`. The leaf name
  alone is ambiguous and retrieves nonsense; the enclosing path is what makes it
  a real question. Build each query as the parameter name plus the LAST ONE OR
  TWO segments of its moHierarchy — not the whole path, which is also wrong.
  Measured on one Cisco payload:
      "input"                     -> "show chassis power"            0.27  wrong
      "service-policy input"      -> Cisco-IOS-XE-ethernet           0.45  right
      "output"                    -> 3GPP TS 38.106 spectrum mask    0.32  wrong
      "service-policy output"     -> show services policies detail   0.47  right
      "import"                    -> 28.623 managed-element          0.51  wrong
      "route-target import vrf"   -> vrf-target                      1.16  right
  So for {"parameterName":"input","moHierarchy":"configuration/interface/service/service-policy"}
  the query is "service-policy input". Bare "input" is never acceptable. Words
  like input, output, name, import, export, forwarding, mask and vrf are the
  worst offenders — they are ordinary English and match everything.
- SEVERAL ITEMS GO IN ONE CALL. When the input holds more than one thing to look
  up — comma-separated alarm names, a list of parameters, several questions —
  pass them all as the `queries` array of the BATCH search tool in a SINGLE call:
      search3gppBatch(queries=["Mpls Tunnel Down", "Mpls Lsp Change", "Mpls Lsp Info Change"])
  The server searches each one separately and returns one JSON object keyed by
  query. Do NOT call the tool once per item, and do NOT join them into one string
  with commas — a joined string is embedded as a single vector and matches none of
  its parts well.
  If no batch tool is listed in your tools, this deployment exposes only the
  single-query tool: call it once per item and finish the whole list before
  refining anything.
- COVER EVERY ITEM BEFORE YOU REFINE ANY OF THEM. Measured on a 3-item list:
  calling once per item, the model re-queried the first two fourteen times, never
  searched the third, and returned nothing at all. One batch call cannot fail that
  way. If you do end up looping, finish the first pass before going back.
- Budget your rounds. You have a hard limit. A batch call plus at most three
  follow-ups should answer anything. Refining is optional; covering every item is not.
- Call tools again with a different wording or filter when the first result is
  weak — but at most ONCE per item, and only after the first pass is complete.
  Note that the confidence banner ("Confidence: low", "the two retrievers
  disagree", "Do not cite [1] alone") is guidance about how to CITE what you
  already have, not an instruction to search again. Low confidence on a hit that
  plainly answers the question is fine: cite it and move on to the next item.

ANSWERING
- Cite the spec ID for every claim, e.g. "TS 38.331 §5.3.3".
- Never answer before calling at least one tool. A tool description that lists a
  corpus gap tells you what will be MISSING from the results, not whether to
  call it — the indexed specs next to the gap usually define the generic
  mechanism the question turns on. Search first, then scope the answer.
- When the tools DID run and returned nothing relevant, say so plainly and name
  what would be needed. A clear "this corpus does not cover X" is a correct
  answer once you have looked; an unverified one is a guess.
- Do not describe the tool calls you made unless asked. Answer the question.
"""

# Appended to AGENT_PROMPT when the question is a LIST. Set UI_ANSWER_FORMAT=table
# to drop it and go back to whatever shape the model picks on its own.
#
# Why it exists: asked about 36 alarms, the model produced a three-column markdown
# table with a prose "Notes" cell per row — roughly 150-200 tokens each, so ~6,000
# for the list. It ran out of completion budget at row 19, mid-sentence, and the
# 17 remaining alarms simply were not there. The same 36 answered one line each
# fit in about 900 tokens. Coverage of every item beats depth on the first few.
LIST_FORMAT_RULE = """

ANSWERING A LIST (several alarms, parameters or names in one question):
- ONE LINE per item, in the order asked. No markdown table, no per-row prose.
      <item> — <spec id> (<object/statement name>)
      <item> — not found
- Every item asked for gets a line, including the ones with no match. An item you
  silently omit reads as an oversight, not as "nothing found".
- Say "not found" whenever the evidence for that item is marked no_match, or none
  of its hits actually name the thing asked about. Do NOT reach for the nearest
  unrelated document: a 3GPP subscriber-data clause is not the definition of a
  vendor hardware trap, and citing it is worse than saying nothing.
- Keep each line to one spec plus, at most, one alternate. Detail is worth less
  than covering the whole list.
- Add any explanation AFTER the list, not inside it."""


def chat_llm(provider, api_key, model, messages, tools=None, timeout=180):
    """OpenAI-compatible chat call. Returns (assistant message dict, error)."""
    cfg = PROVIDERS[provider]
    # temperature 0, not 0.1. At 0.1 the SAME payload produced different tool
    # calls run to run: one run queried "Mpls Tunnel Down" with no filter and
    # got RFC 3985 at 0.53; the next added docType='CLI' plus the word "alarm",
    # scored 0.07, and gave up with a clarifying question. The variance was not
    # in the corpus — it was in which filter the model happened to invent.
    body = {"model": model or cfg["model"],
            "temperature": float(os.environ.get("UI_AGENT_TEMPERATURE", "0")),
            "messages": messages}
    # Was unset, i.e. whatever the provider defaults to. A 36-alarm question came
    # back as a table that stopped mid-sentence at row 19 — the model had run out
    # of completion budget, and because finish_reason was discarded below, nothing
    # said so. Set UI_AGENT_MAX_TOKENS= (empty) to go back to the provider default.
    _mt = os.environ.get("UI_AGENT_MAX_TOKENS", "8000").strip()
    if _mt:
        body["max_tokens"] = int(_mt)
    if tools:
        body["tools"] = tools
        body["tool_choice"] = "auto"
    req = urllib.request.Request(
        cfg["url"], data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json",
                 "Authorization": f"Bearer {api_key}",
                 "User-Agent": "3gpp-mcp-ui/1.0"},
        method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode())
        choice = data["choices"][0]
        msg = choice["message"]
        # Carried on the message so run_agent can tell a finished answer from one
        # the provider cut off. Underscore-prefixed: this is ours, not the API's,
        # and must never be echoed back to the model as part of the conversation.
        msg["_finish_reason"] = choice.get("finish_reason")
        return msg, None
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")[:600]
        return None, f"{provider} HTTP {e.code} ({cfg['url']}): {detail}"
    except Exception as e:  # noqa: BLE001 — surfaced to the UI verbatim
        return None, f"{provider} call failed: {e}"


# A batch search3gpp reply is one block per query, each opening with this header.
_BATCH_HDR = re.compile(r"^={60,}\nQUERY (\d+)/(\d+): .*\n={60,}$", re.M)

# Ceiling on a batch reply after clipping. TOOL_RESULT_MAX is sized for ONE
# result; a batch legitimately needs more, but not without bound — the whole
# conversation is re-sent every round, and a provider's tokens-per-minute
# ceiling is what actually breaks first. Measured: 10 queries at topK=5 return
# 63,559 chars (~15,900 tokens) raw, and gpt-oss-20b on Groq's 50k TPM tier
# already 429s at ~22k tokens per request.
# Ceiling on a batch reply after clipping.
#
# This was a flat 24,000 chars, which is about what FIVE queries need. Anything
# larger was squeezed regardless: a 10-alarm list arrived at 41,413 chars raw and
# came back with excerpts cut to 500 and 6 hits dropped. That is not free — on
# "Ospf Nbr State Change" the 500-char cut of RFC 4750 ended inside
# ospfVirtIfStateChange, before ospfNbrStateChange appeared, and the model cited
# the virtual-interface trap because that is what it could see. A budget that
# does not grow with the list turns a longer question into a wronger answer.
#
# Now: 5,000 chars per query (one query's five hits plus metadata), capped.
# Measured raw sizes at topK=5, verbosity=brief:  5 q → 23,219   10 q → 41,413
# 20 q → 63,766. The cap is what keeps a 20-query call from eating the whole
# context; Groq's free tier is 50,000 tokens/MINUTE and the conversation is
# re-sent every round, so ~10k tokens per tool result is the practical ceiling.
BATCH_CHARS_PER_QUERY = int(os.environ.get("UI_BATCH_CHARS_PER_QUERY", "5000"))
BATCH_RESULT_MAX = int(os.environ.get("UI_BATCH_RESULT_MAX", "40000"))


def _batch_budget(n_queries):
    """Chars this reply may occupy — scales with the list, capped."""
    if n_queries <= 0:
        return BATCH_RESULT_MAX
    return min(BATCH_RESULT_MAX, BATCH_CHARS_PER_QUERY * n_queries)
# Below roughly this, a section cannot hold even one whole chunk (~2,500 chars
# for RFC prose) and the model starts re-querying instead of reading.
BATCH_MIN_PER_QUERY = 900


def _clip_json_batch(result: str):
    """Trim a JSON batch reply by shortening excerpts, keeping the JSON valid.

    A flat cut is not an option here: slicing a JSON document in the middle makes
    it unparseable, which is strictly worse than the text form it replaced. The
    document is re-serialised instead, with each query's excerpts shortened until
    the whole thing fits. Every query keeps its hits — a batch degrades in depth,
    never in coverage.

    Returns the clipped string, or None if the input is not a JSON batch.
    """
    t = result.lstrip()
    if not t.startswith("{"):
        return None
    try:
        doc = json.loads(result)
    except (json.JSONDecodeError, ValueError):
        return None
    if not isinstance(doc, dict) or "_meta" not in doc:
        return None
    queries = [k for k in doc if k != "_meta"]
    if not queries:
        return result
    budget_max = _batch_budget(len(queries))
    if len(result) <= budget_max:
        return result

    def _blocks():
        for q in queries:
            if isinstance(doc[q], dict):
                yield doc[q]

    def _size():
        return len(json.dumps(doc, separators=(",", ":")))

    def _finish(budget, dropped):
        # Always say what was done, including when we ran out of room and gave up.
        # The version this replaces set the note only on a budget that FIT; on a
        # 36-query call nothing fit, so it returned 28,126 chars of 160-char
        # excerpts with a _meta that claimed no trimming at all. Silent trimming
        # is how a shredded excerpt gets read as the whole clause.
        meta = doc["_meta"]
        if budget:
            meta["excerpts_trimmed_to_chars"] = budget
        if dropped:
            meta["hits_dropped"] = dropped
        if budget or dropped:
            over = _size() > budget_max
            meta["note"] = (
                "shortened to fit the caller's context"
                + (f"; {dropped} lower-scoring hit(s) removed" if dropped else "")
                + ". Every query is still represented"
                + (" and this STILL exceeds the budget — treat excerpts as fragments"
                   if over else "")
                + ". Ask for one query alone to see it in full.")
        return json.dumps(doc, separators=(",", ":"))

    # Stage 1 — shorten excerpts, but not past the point of being a readable
    # sentence. 160 chars was the old floor and it is barely a clause fragment.
    budget_used = None
    for budget in (1200, 800, 500):
        for b in _blocks():
            for h in b.get("hits") or []:
                ex = h.get("excerpt") or ""
                if len(ex) > budget:
                    h["excerpt"] = ex[:budget].rstrip() + " …[trimmed]"
                    budget_used = budget
        if _size() <= budget_max:
            return _finish(budget_used, 0)

    # Stage 2 — still over. Now DROP hits instead of shredding text further:
    # a query's 5th-ranked hit is worth less than the top hit's readable excerpt.
    # Hits arrive score-ordered, so removing from the end removes the weakest.
    # Never below one hit per query — coverage is what a batch is for.
    dropped = 0
    for keep in (4, 3, 2, 1):
        for b in _blocks():
            hits = b.get("hits") or []
            if len(hits) > keep:
                dropped += len(hits) - keep
                b["hits"] = hits[:keep]
                b["hits_truncated_to"] = keep
        if _size() <= budget_max:
            return _finish(budget_used, dropped)

    # Stage 3 — one hit each and still over. Squeeze the remaining excerpts.
    for budget in (300, 160):
        for b in _blocks():
            for h in b.get("hits") or []:
                ex = h.get("excerpt") or ""
                if len(ex) > budget:
                    h["excerpt"] = ex[:budget].rstrip() + " …[trimmed]"
                    budget_used = budget
        if _size() <= budget_max:
            return _finish(budget_used, dropped)
    return _finish(budget_used, dropped)


def clip_tool_result(result: str) -> str:
    """Trim a tool result to fit the context, without dropping whole queries.

    A flat head-truncation is wrong for a batch reply. Measured on the old text
    format: 10 queries at topK=5 produced 63,559 chars, so a 6,000-char cut left
    the first two and silently discarded the other eight — and the model then
    answered as though it had covered all ten. Batches are handled per query
    instead, so they lose depth rather than coverage.
    """
    clipped = _clip_json_batch(result)
    if clipped is not None:
        return clipped

    # Legacy text batches (kept: an older server, or a replayed transcript).
    heads = list(_BATCH_HDR.finditer(result))
    if len(heads) >= 2:
        n = len(heads)
        budget_max = _batch_budget(n)
        if len(result) <= budget_max:
            return result
        per = max(BATCH_MIN_PER_QUERY, budget_max // n)
        out = [result[:heads[0].start()]]
        trimmed = 0
        for i, m in enumerate(heads):
            end = heads[i + 1].start() if i + 1 < n else len(result)
            section = result[m.start():end]
            if len(section) <= per:
                out.append(section)
                continue
            trimmed += 1
            out.append(section[:per].rstrip()
                       + f"\n…[this query's results truncated at {per} chars]\n")
        if trimmed:
            out.append(f"\n…[{trimmed} of {n} queries were truncated to keep every query "
                       f"represented.]")
        return "".join(out)

    if len(result) <= TOOL_RESULT_MAX:
        return result
    return (result[:TOOL_RESULT_MAX]
            + f"\n…[truncated at {TOOL_RESULT_MAX} chars — narrow the query "
              "or lower topK to see the rest]")


def run_agent(provider, api_key, model, question, tools, max_steps=AGENT_MAX_STEPS):
    """Let the model drive the MCP tools. Returns (answer, trace, error).

    A plain OpenAI tool-calling loop: offer the server's tools, run whatever the
    model asks for, feed the results back, repeat until it answers. Which tool
    runs, with which arguments, and how many times is entirely the model's call —
    this function never inspects the question.
    """
    system = AGENT_PROMPT
    if os.environ.get("UI_ANSWER_FORMAT", "lines").strip().lower() != "table" \
            and split_list_items(question):
        system += LIST_FORMAT_RULE
    messages = [{"role": "system", "content": system},
                {"role": "user", "content": question}]
    trace = []
    refused_once = False
    # Every (tool, arguments) pair already executed. A repeat is never new
    # information — the corpus does not change mid-run — so re-running it burns
    # a step, ~2.5 s and a full context re-send for a result the model has
    # already seen. Measured on one Nokia payload: 15 steps, of which
    # search3gpp("export-grt", series=JUNIPER) ran 5 times identically and
    # search3gpp("grt-lookup Junos") twice, then the run died on the step limit
    # with no answer. Short-circuiting those would have left 8 steps for
    # something useful.
    executed: dict[tuple, int] = {}
    for step in range(max_steps):
        msg, err = chat_llm(provider, api_key, model, messages, tools=tools)
        if err:
            return None, trace, err
        messages.append({
            "role": "assistant",
            "content": msg.get("content") or "",
            **({"tool_calls": msg["tool_calls"]} if msg.get("tool_calls") else {}),
        })
        calls = msg.get("tool_calls") or []
        if not calls:
            # A first-turn answer with no tool call is always ungrounded — the
            # model is reciting the corpus-gap list from a tool description
            # rather than searching. Push back once; if it insists, let it
            # through so a genuinely unanswerable question still terminates.
            if not trace and not refused_once:
                refused_once = True
                messages.append({"role": "user", "content":
                    "You have not called any tool yet, so that answer is not "
                    "grounded. Call the most relevant tool first, then answer "
                    "from what it returns."})
                continue
            if msg.get("_finish_reason") == "length":
                # The provider stopped mid-answer. Say so in the answer itself —
                # a table that ends mid-row otherwise reads as a complete result,
                # and the reader has to notice the missing rows by eye.
                return ((msg.get("content") or "")
                        + "\n\n---\n**⚠ This answer was cut off — the model hit its "
                          "output limit before finishing. Items after the last "
                          "complete line were NOT covered. Ask for the remainder, "
                          "or raise UI_AGENT_MAX_TOKENS."), trace, None
            return msg.get("content"), trace, None
        for c in calls:
            fn = (c.get("function") or {})
            name = fn.get("name") or ""
            raw_args = fn.get("arguments") or "{}"
            try:
                args = json.loads(raw_args) if isinstance(raw_args, str) else (raw_args or {})
            except ValueError:
                args = {}
            # Canonical key: same tool, same arguments, regardless of key order.
            key = (name, json.dumps(args, sort_keys=True, default=str))
            t0 = time.time()
            if key in executed:
                prev = executed[key]
                result = (
                    f"NOT RE-RUN — this exact call was already made at step {prev}, "
                    f"and the corpus has not changed since. Scroll up for its result.\n"
                    f"Repeating a query cannot surface anything new. Do one of:\n"
                    f"  - query a DIFFERENT term (the parameter name alone, or its "
                    f"parent statement),\n"
                    f"  - drop the series/docType filters if you set any,\n"
                    f"  - or answer now from what you already have, saying plainly "
                    f"what the corpus does and does not cover."
                )
                error = None
            else:
                executed[key] = step + 1
                try:
                    result = mcp_call(name, args)
                    error = None
                except Exception as e:  # noqa: BLE001 — the model gets to see and retry
                    result, error = f"Tool call failed: {e}", str(e)
            ms = int((time.time() - t0) * 1000)
            clipped = clip_tool_result(result)
            trace.append({"step": step + 1, "tool": name, "arguments": args,
                          "ms": ms, "error": error, "chars": len(result),
                          "result": clipped})
            messages.append({"role": "tool", "tool_call_id": c.get("id"),
                             "name": name, "content": clipped})
    return None, trace, (f"stopped after {max_steps} tool-calling rounds without a "
                         "final answer — raise UI_AGENT_MAX_STEPS if this is legitimate")


# ── Response parsing ─────────────────────────────────────────────────────────

HIT_RE = re.compile(
    r"^\[(?P<n>\d+)\]\s+(?P<spec>[^\s|]+)\s+\|\s+(?P<release>[^|]*?)\s+\|\s+Score:\s*(?P<score>[\d.]+)\s*$",
    re.M)
FIELD_RE = re.compile(r"^\s{4}(?P<key>Title|Series|Key|Excerpt|More)\s*:\s*(?P<val>.*)$")


def _parse_hits_json(text):
    """Hits out of a JSON batch reply, or None if this is not one.

    search3gpp answers a `queries` batch as a JSON map keyed by query, so the
    line-oriented parser below finds nothing in it and the UI would report zero
    hits while `raw` plainly held them. Same output shape either way: the caller
    sees one flat list, with `query` naming which item each hit came from.
    """
    t = text.lstrip()
    if not t.startswith("{"):
        return None
    try:
        doc = json.loads(text)
    except (json.JSONDecodeError, ValueError):
        return None
    if not isinstance(doc, dict) or "_meta" not in doc:
        return None
    hits = []
    for query, block in doc.items():
        if query == "_meta" or not isinstance(block, dict):
            continue
        for h in block.get("hits") or []:
            hits.append({"n": h.get("n"), "spec_id": h.get("spec_id", ""),
                         "release": h.get("release", ""),
                         "score": h.get("score"), "title": h.get("title", ""),
                         "series": h.get("series", ""),
                         "excerpt": h.get("excerpt", ""),
                         "query": query})
    return hits



def hits_by_query(raw, hits):
    """Group hits under the query that produced them, or None for a single query.

    The MCP tool answers a batch as a JSON map keyed by query; `hits` flattens it
    so the existing single-query consumers keep working unchanged. This restores
    the map alongside it, so a caller asking about four parameters gets four
    labelled buckets instead of having to filter one list by hit["query"].
    """
    t = (raw or "").lstrip()
    if not t.startswith("{"):
        return None
    try:
        doc = json.loads(raw)
    except (json.JSONDecodeError, ValueError):
        return None
    if not isinstance(doc, dict) or "_meta" not in doc:
        return None
    grouped = {}
    for h in hits:
        q = h.get("query")
        if q:
            grouped.setdefault(q, []).append(h)
    # Keep the tool's own order, and keep a query that returned nothing visible.
    out = {}
    for q, block in doc.items():
        if q == "_meta":
            continue
        out[q] = {"hits": grouped.get(q, [])}
        if isinstance(block, dict):
            for k in ("intent", "margin", "top_score", "distinct_specs",
                      "retrievers_agree", "coverage_note", "error"):
                if k in block:
                    out[q][k] = block[k]
    return out


def parse_hits(text):
    """Turn the server's formatted search output into structured hits."""
    from_json = _parse_hits_json(text)
    if from_json is not None:
        return from_json
    lines = text.splitlines()
    hits, current = [], None
    for line in lines:
        m = HIT_RE.match(line)
        if m:
            if current:
                hits.append(current)
            current = {"n": int(m.group("n")), "spec_id": m.group("spec"),
                       "release": m.group("release").strip(),
                       "score": float(m.group("score")),
                       "title": "", "series": "", "excerpt": ""}
            continue
        if current is None:
            continue
        f = FIELD_RE.match(line)
        if not f:
            continue
        key, val = f.group("key"), f.group("val").strip()
        if key == "Title":
            current["title"] = val
        elif key == "Series":
            current["series"] = val
        elif key in ("Key", "Excerpt"):
            current["excerpt"] = val
    if current:
        hits.append(current)
    return hits


LAYER_RE = re.compile(r"^=== (.+?) \(series (\S+)\) ===$", re.M)
PROC_HIT_RE = re.compile(
    r"^\s{2}(?P<spec>\S+)\s+\|\s+(?P<release>\S+)\s+\|\s+chunk\s+(?P<chunk>-?\d+)\s+\|\s+score\s+(?P<score>[\d.]+)$")
EMPTY_RE = re.compile(r"^No strong evidence in:\s*(.+)$", re.M)


def parse_procedure(text):
    """Turn getProcedureFlow's grouped output into layers -> hits."""
    layers, current, hit = [], None, None
    for line in text.splitlines():
        m = LAYER_RE.match(line)
        if m:
            current = {"label": m.group(1), "series": m.group(2), "hits": []}
            layers.append(current)
            hit = None
            continue
        if current is None:
            continue
        h = PROC_HIT_RE.match(line)
        if h:
            hit = {"spec_id": h.group("spec"), "release": h.group("release"),
                   "chunk": int(h.group("chunk")), "score": float(h.group("score")),
                   "text": ""}
            current["hits"].append(hit)
            continue
        if hit is not None and line.startswith("    "):
            hit["text"] = (hit["text"] + " " + line.strip()).strip()
    empty = EMPTY_RE.search(text)
    return layers, ([x.strip() for x in empty.group(1).split(";") if x.strip()] if empty else [])


# ── Input routing ────────────────────────────────────────────────────────────
# Routing used to be syntactic: anything starting '{' and ending '}', or longer
# than 600 chars, or with more than 8 newlines, was declared a "document" and
# diverted into the Bulk-CM audit path. That misroutes ordinary questions —
#     {"query": "VPN Pseudowire Down leading to Link Down", "max_results": 5}
# is a question wrapped in JSON, but the brace test sent it to the CM extractor,
# which found no parameters and returned an empty compliance report. The question
# never reached the retriever at all.
#
# Shape does not determine intent, so the model decides now (ROUTER_PROMPT). The
# only thing left in code is the no-LLM fallback below.


# Keys a tool-call envelope uses for the actual question. Pasting
# {"query": "...", "max_results": 5} is a natural thing to do — it is what the
# MCP request looks like — and treating it as a document to mine for
# configuration items produces an empty extraction and a confusing error.
ENVELOPE_KEYS = ("query", "question", "q", "text", "prompt", "search")


def unwrap_envelope(q):
    """A small JSON wrapper around a question -> the question itself, else None."""
    t = (q or "").strip()
    if not (t.startswith("{") and t.endswith("}")) or len(t) > 2000:
        return None
    try:
        obj = json.loads(t)
    except ValueError:
        return None
    if not isinstance(obj, dict):
        return None
    for k in ENVELOPE_KEYS:
        v = obj.get(k)
        if isinstance(v, str) and v.strip():
            return v.strip()
    return None


def document_reason(q):
    """Why this input is a document rather than a question, or None.

    Nothing document-shaped is forwarded to the retriever: a payload averaged
    into one embedding scored 0.15 — the noise floor — with every result tied.
    Matching the server-side guard keeps the payload off the wire entirely.
    """
    t = (q or "").strip()
    if len(t) > 600:
        return f"it is {len(t):,} characters (a search query should be a short phrase)"
    # A short JSON blob is far more likely to be a pasted request envelope than a
    # document worth mining, and there is nothing to extract from it either way.
    if (t.startswith("{") and t.endswith("}")) or (t.startswith("[") and t.endswith("]")):
        return "it is a JSON object/array" if len(t) > 300 else None
    if t.startswith("<?xml") or (t.startswith("<") and t.endswith(">") and "</" in t):
        return "it is an XML document"
    if t.count("\n") > 8:
        return "it spans many lines and looks like pasted content"
    return None


def split_list_items(q):
    """A question that is really N questions → its items, else [].

    Recognised by shape, not by asking: three or more short comma- or
    newline-separated fragments, none of them a sentence. That is what an alarm
    or parameter list looks like, and it is the case where covering every item
    matters more than depth on the first few.

    Kept deliberately strict. "What is the difference between X, Y and Z?" also
    has commas, but it ends in a question mark and reads as one question — the
    per-item format would only get in the way there.
    """
    t = (q or "").strip()
    if not t or t.startswith("{") or t.endswith("?"):
        return []
    parts = [p.strip() for p in re.split(r"[,\n]", t)]
    items = [p for p in parts if p]
    if len(items) < 3:
        return []
    for it in items:
        if len(it) > 60 or len(it.split()) > 8 or "?" in it:
            return []
    return items


def list_coverage(question, answer, trace):
    """For a list question: which items were searched, and which reached the answer.

    Two independent ways to lose an item, both of which happened on one 36-alarm
    run and neither of which was visible in the response:
      - never searched — the batch cap dropped the tail of a `queries` array and
        the model did not send a follow-up call. 6 alarms, 3 of them among the
        best-scoring in the set.
      - searched but unanswered — the model's reply hit the completion limit at
        item 19 of 36.
    Counting is cheap and the alternative is the reader spotting it by eye.
    Returns None when the question is not a list. Set UI_COVERAGE_CHECK=0 to skip.
    """
    if os.environ.get("UI_COVERAGE_CHECK", "1").strip() == "0":
        return None
    items = split_list_items(question)
    if not items:
        return None
    # Count what actually RAN, from the result — not what was requested. The
    # batch tool caps each call, so `arguments.queries` over-reports: on one
    # 36-alarm run this said 36/36 searched while 16 had been dropped over the
    # cap and answered "not found". The result map is the only honest source.
    searched = set()
    for step in trace:
        res = step.get("result") or ""
        if res.lstrip().startswith("{"):
            try:
                doc = json.loads(res)
            except (json.JSONDecodeError, ValueError):
                doc = {}
            for q, block in (doc.items() if isinstance(doc, dict) else []):
                if q == "_meta" or not isinstance(block, dict):
                    continue
                if not block.get("not_searched"):
                    searched.add(str(q).strip().lower())
        else:
            one = (step.get("arguments") or {}).get("query")
            if one:
                searched.add(str(one).strip().lower())
    body = (answer or "").lower()
    not_searched = [i for i in items if i.lower() not in searched]
    # Substring match, not exact: the model reformats an item into its own line
    # ("Jnx Fan Failure — JUNIPER-MIB"), so the item text is present but not alone.
    unanswered = [i for i in items if i.lower() not in body]
    out = {"items": len(items),
           "searched": len(items) - len(not_searched),
           "in_answer": len(items) - len(unanswered)}
    if not_searched:
        out["never_searched"] = not_searched
    if unanswered:
        out["missing_from_answer"] = unanswered
    return out


def looks_like_cm_payload(q):
    """A Bulk-CM non-compliance export, whatever mode the user happens to be in.

    Recognised by structure rather than by the user telling us: a JSON object
    carrying a list of parameter entries. Asking the user to pick the right mode
    first is friction the tool should absorb — the input already says what it is.
    """
    t = (q or "").lstrip()
    if not t.startswith("{"):
        return False
    try:
        obj = json.loads(t)
    except ValueError:
        return False
    data = obj.get("data", obj) if isinstance(obj, dict) else {}
    items = data.get("nonCompliancedata") if isinstance(data, dict) else None
    return isinstance(items, list) and bool(items) and any(
        isinstance(x, dict) and x.get("parameterName") for x in items)


# ── Bulk-CM compliance audit ─────────────────────────────────────────────────
# A Bulk-CM payload is not one question, it is N questions. Sending the whole
# document as a single query is why it failed: 60 parameters averaged into one
# embedding scores at the floor (measured top=0.15, margin=0.002 — every result
# tied, i.e. nothing matched). This splits it back into answerable questions.

# Vendor CM attribute → 3GPP IE name, read from the SERVER's own alias table so
# there is exactly one source of truth. The server applies these inside its BM25
# AND path, but the dense/embedding half of the query still sees whatever wording
# the caller sent — and the wording matters enormously here. Measured, series 36:
#     "rootSeqIndex …"            -> 36.331 at 0.039  (confidence none)
#     "prach-RootSequenceIndex …" -> 36.331 at 0.364  (confidence medium)
# Same spec, same index, ~9x the score. So expand the name before querying.
ALIAS_TSV = HERE.parent / "src" / "main" / "resources" / "retrieval" / "and-term-subst.tsv"


def load_aliases():
    out = {}
    try:
        for line in ALIAS_TSV.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t", 1)
            if len(parts) == 2:
                out[parts[0].strip().lower()] = parts[1].strip()
    except OSError as e:
        print(f"[ui] alias table unreadable ({e}) — parameter names sent verbatim",
              file=sys.stderr)
    return out


ALIASES = load_aliases()


def spec_term(param):
    """Vendor attribute name → the spec's own IE name, when we know it."""
    if not param:
        return ""
    key = re.sub(r"[^A-Za-z0-9-]", "", param).lower()
    alias = ALIASES.get(key)
    # REPLACE the vendor name rather than appending to it. Keeping both dilutes
    # the query badly — measured on "rootSeqIndex", series 36:
    #     "rootSeqIndex rootsequenceindex <ctx> allowed values range" -> 36.423 @ 0.108
    #     "rootsequenceindex <ctx>"                                   -> 36.331 @ 0.346
    # The vendor token contributes nothing (it appears nowhere in the specs) and
    # drags the embedding away from the clause that actually defines the IE.
    return alias if alias and alias.lower() != key else param


# technology → 3GPP series that owns its radio/RRC parameters.
TECH_SERIES = {"lte": "36", "4g": "36", "eps": "36", "5g": "38", "nr": "38", "5gs": "38"}

# vendor → the series under which that vendor's own configuration model is
# indexed. TECH_SERIES only knows radio technologies, so a TRANSPORT/COMMON
# payload got series="" and its short queries drifted into the 3GPP OAM specs,
# which are full of the words "configuration", "system" and "community".
# Measured, Juniper payload, unfiltered -> series=JUNIPER:
#     rd-type instance route-distinguisher  28.510  0.03 -> route-distinguisher 1.05
#     version configuration                32.106-1 0.35 -> Junos request…      1.01
#     description interface unit           (generic) 0.34 -> use-interface      1.00
#     community term from                    32.404 0.15 -> community statement 0.22
# Trade-off, stated because it is real: the filter also hides the IETF RFCs. On
# this payload that cost nothing — "key authentication md5" moved from RFC 5880
# (BFD keyed MD5) to the Junos OSPF authentication statement at 1.11, which is
# the better answer for a Junos config audit. It would cost something on an
# ALARM payload, where the defining document is usually an RFC or a MIB, so this
# applies to the config-audit path only and `series` in the request overrides it.
VENDOR_SERIES = {"juniper": "JUNIPER", "cisco": "CISCO", "nokia": "NOKIA"}

# Trailing path segments that carry no topic information. "from"/"then" are Junos
# match/action blocks — they end a policy path and say nothing about the topic.
_PATH_NOISE = {"attributes", "configdata", "bulkcmconfigdatafile", "from", "then"}


def mo_context(mo_hierarchy, param):
    """Managed-object path → the nearest meaningful parent names.

    "/…/ioc:SIB1NB/ioc:SIB1NBSchedulingInfo/ioc:attributes/ioc:si-Periodicity"
    becomes "SIB1NB SIB1NBSchedulingInfo" — the context that distinguishes this
    parameter from a same-named one elsewhere in the tree.

    Window size depends on the payload's shape. A 3GPP Bulk-CM path names an
    Information Object Class per segment ("ioc:"), so two segments are already
    specific and a third only dilutes. A vendor config path spends its tail on
    generic containers (…/policy-statement/term/from, …/interface/unit), so two
    segments can miss the discriminating name entirely. Measured over 14 Juniper
    parameters, series=JUNIPER: window 2 scored 13.59 total, window 3 scored
    14.16, and the worst case went 0.215 -> 1.113 ("community term from" ->
    "community policy-options policy-statement term"). The three regressions were
    all under 0.06 and kept the same top document.
    """
    if not mo_hierarchy:
        return ""
    window = 2 if ":" in mo_hierarchy else 3
    parts = []
    for seg in mo_hierarchy.split("/"):
        seg = seg.split(":")[-1].strip()
        if not seg or seg.lower() in _PATH_NOISE:
            continue
        if seg.lower() == (param or "").lower():
            continue
        parts.append(seg)
    return " ".join(parts[-window:])


def build_param_query(item, ie_override=None):
    """One short, spec-flavoured question for a single parameter.

    ie_override comes from the planning pass — the model's view of the spec's own
    name for this IE, which beats the static alias table because it generalises
    to vendor spellings nobody enumerated in advance.
    """
    name = (item.get("parameterName") or "").strip()
    if ie_override:
        return " ".join(x for x in [ie_override.strip(),
                                    mo_context(item.get("moHierarchy"), name)] if x).strip()
    ctx = mo_context(item.get("moHierarchy"), name)
    # No "allowed values range" boilerplate: it is generic filler that appears in
    # thousands of chunks and measurably dilutes the query (si-Periodicity dropped
    # 0.399 -> 0.073 with it appended). The MO context is the useful disambiguator.
    return " ".join(x for x in [spec_term(name), ctx] if x).strip()


def param_is_present(param, alias, hits):
    """Does the retrieved evidence actually contain the parameter we asked about?

    This is ground truth, not a proxy. The confidence tier answers "is the
    RANKING well separated", which is the wrong question here: a narrow IE lookup
    legitimately scores ~0.05 because the retrieved chunk is 95% about other
    parameters, so `none` fires on perfectly good evidence. Measured — the query
    "maxretxthreshold NBIOTService RLCNB" was labelled `none` while returning the
    chunk that reads "7.4 Configurable parameters ... a) maxRetxThreshold This
    parameter is used by the transmitting side of each AM RLC entity to limit the
    number of retransmissions". Keying the verdict on the score threw that away.
    """
    # Check the EVIDENCE ONLY. The tool echoes the query back as
    # 'Search results for: "..."', so testing the whole response makes every
    # parameter look present — including radioSerialNum and antennaBearing,
    # which are vendor/AISG attributes that genuinely appear in no 3GPP spec.
    hay = " ".join((h.get("excerpt") or "") + " " + (h.get("title") or "")
                   for h in (hits or [])).lower()
    for term in filter(None, [param, alias]):
        t = term.lower()
        if t in hay:
            return True
        # Specs hyphenate where vendors camelCase: siPeriodicity vs si-Periodicity.
        if t.replace("-", "") in hay.replace("-", ""):
            return True
    return False


ROUTER_PROMPT = """You route input to a 3GPP specification retriever.

Decide which of two things the input is, and return JSON only.

1. A QUESTION — someone asking about telecom specifications, however it is
   phrased or wrapped. It is still a question when it arrives inside JSON, XML,
   quotes or a code fence: unwrap it and use the text that carries the intent.
   Alarm names, error strings, message names, procedures and IE names asked
   about on their own are all questions.

2. A DOCUMENT — a configuration export, audit report or parameter table holding
   MANY separate items that each need their own lookup.

Judge by CONTENT, never by punctuation, bracket characters or length.
{"query": "VPN Pseudowire Down leading to Link Down", "max_results": 5} is a
QUESTION that happens to be wrapped in JSON — the JSON is transport, the question
is the payload. A list of forty attribute/value/path triples is a DOCUMENT.
When it could be read either way, choose QUESTION: a question sent down the
document path returns nothing at all, while a document sent down the question
path still returns usable results.

For a QUESTION, also rewrite it as a retrieval query:
  - keep the technical terms, drop conversational filler and transport wrappers
  - prefer the specification's own vocabulary where it differs from vendor or
    operator wording
  - do not add words like "allowed values" or "range" — generic filler appears in
    thousands of chunks and measurably dilutes the query
  - set "series" ONLY if the input names a technology: "36" for LTE/EPS/E-UTRAN,
    "38" for 5G/NR/5GS, "" otherwise. Never guess it.

OUTPUT — one JSON object, no prose and no markdown fence:
  {"kind":"question","query":"<retrieval query>","series":""}
  {"kind":"document"}
"""


EXTRACT_PROMPT = """You are the first stage of a 3GPP specification lookup pipeline.

The user has pasted a DOCUMENT — a configuration export, an audit report, a table,
a list of parameters. It may be JSON, XML, CSV or free text, from any vendor.

Your only job is to decide WHAT IS WORTH LOOKING UP in the 3GPP specifications, and
to express each of those as a lookup the retriever can actually answer. You do NOT
answer anything and you do NOT call any tool.

WHAT TO EXTRACT
For every distinct configuration item in the document, emit one object:

  parameter    the item's name exactly as the document writes it
  ieName       the SPECIFICATION's name for it (see TRANSLATE below)
  context      the 1-3 nearest enclosing object names, if the document shows a path.
               From "/…/ioc:SIB1NB/ioc:SIB1NBSchedulingInfo/ioc:attributes/ioc:si-Periodicity"
               emit "SIB1NB SIB1NBSchedulingInfo". This disambiguates an IE that
               appears in several places with different permitted values.
  series       "36" if the document says LTE / EPS / E-UTRAN,
               "38" if it says 5G / NR / 5GS, "" if it does not say.
  expected     the golden / reference / expected value, if present
  actual       the current / configured / observed value, if present
  is3gpp       true if 3GPP defines this item, false otherwise
  reason       one short clause, only when is3gpp is false

TRANSLATE VENDOR NAMES TO SPEC NAMES
Vendors rename information elements; the spec text does not contain the vendor
spelling, so a query using it finds nothing. Convert:
    siPeriodicity -> si-Periodicity          rootSeqIndex -> rootSequenceIndex
    prachIndex -> prach-ConfigIndex          ulMaxRetxThreshold -> maxRetxThreshold
    ulTPollRetransmit -> t-PollRetransmit    pci -> physCellId
    preambleTargetPower -> preambleInitialReceivedTargetPower
    ueDataInactivityTimer -> dataInactivityTimer      tac -> trackingAreaCode
These show the PATTERN, not a closed list: specs hyphenate where vendors camelCase,
drop directional ul/dl prefixes the spec expresses structurally, and spell
abbreviations out. Apply the same reasoning to names not listed. When you cannot
work out the spec's spelling, repeat the vendor name and set "confident": false.

WHAT IS NOT 3GPP — set is3gpp false
  serial numbers, base-station and hardware inventory identifiers
  antenna azimuth, tilt, bearing, RET/RTS values          (these are AISG)
  alarm names, descriptions, severities, free-text labels
  vendor feature switches and capacity limits (maxActiveUsers, maxDataBearers)
3GPP DOES define: RRC, MAC, RLC, PDCP, PHY, SIB contents, timers, thresholds,
measurement events, identifiers.

RULES
- One object per DISTINCT item. If the same parameter appears twice under different
  paths, emit both — their permitted values may differ.
- Never invent an item that is not in the document.
- Marking a hardware attribute as 3GPP wastes one lookup; marking a real IE as
  non-3GPP loses a compliance check, which is worse. When genuinely unsure, set
  is3gpp true.
- If the document contains no configuration items at all, return [].

OUTPUT
A JSON array and nothing else — no prose, no markdown fence:
[{"parameter":"siPeriodicity","ieName":"si-Periodicity",
  "context":"SIB1NB SIB1NBSchedulingInfo","series":"36",
  "expected":"rf64","actual":"rf1024","is3gpp":true,"confident":true}]"""


PLAN_PROMPT = """You are triaging a configuration export before it is looked up
against an indexed corpus of telecom standards AND vendor configuration models.

You get a list of managed-object attributes: a vendor attribute name and its
managed-object path. For EACH one decide two things:

1. Is this attribute DOCUMENTED IN THE CORPUS at all?
   The corpus is not 3GPP-only. It holds, with chunk counts:
     Cisco IOS-XE YANG configuration model   42,951
     Juniper Junos CLI Reference             28,301
     Nokia SR OS YANG configuration model    17,535
     SNMP MIBs (vendor and IETF)             10,627
     IETF RFCs                                2,596
     3GPP TS/TR                              91,963
     plus ITU-T, ETSI NFV, O-RAN, GSMA, MEF, TM Forum
   So a ROUTER or TRANSPORT configuration attribute — service-policy, vrf,
   route-target, prefix-list, mtu, auth-port, qos-group, bgp, ospf, mpls — IS in
   the corpus, under the vendor's own model. Answer true for those. This is not a
   3GPP-only question, and answering "not 3GPP" for a Cisco or Junos parameter
   drops a lookup that would have succeeded: measured on one Cisco payload,
   marking all five as out-of-scope produced zero retrievals, while the same five
   with their hierarchy attached returned the exact vendor nodes (e.g.
   "input service service-policy" -> Cisco-IOS-XE-ethernet
   /native/interface/.../service-policy/input at 0.41).
   3GPP radio/protocol configuration (RRC, MAC, RLC, PDCP, PHY, SIB contents,
   timers, thresholds, measurement events) is also in scope.
   What is genuinely NOT in the corpus: serial numbers, base-station IDs, antenna
   azimuth/tilt/bearing (AISG), alarm severities, free-text labels and
   descriptions, and site or inventory metadata. Only those get false.

2. If it IS in the corpus, what name does the owning document use? For a vendor
   parameter that is almost always the name as given — Cisco and Nokia YANG and
   the Junos CLI Reference use the operator-facing spelling, so return it
   unchanged. Renaming only applies to 3GPP, where vendors rename things:
      siPeriodicity        -> si-Periodicity
      rootSeqIndex         -> rootSequenceIndex
      prachIndex           -> prach-ConfigIndex
      ulMaxRetxThreshold   -> maxRetxThreshold
      ulTPollRetransmit    -> t-PollRetransmit
      preambleTargetPower  -> preambleInitialReceivedTargetPower
   Use the spec's exact spelling and hyphenation. If you are not confident of the
   spec name, return the vendor name unchanged and set "confident": false.

Return ONLY a JSON array, no prose, no markdown fence. One object per input, in
the same order:
  [{"parameter":"<vendor name>","ieName":"<spec IE name>","is3gpp":true,
    "confident":true,"reason":"<short>"}]

Keep the key name "is3gpp" — it now means "is in the corpus", vendor models
included. The name is historical; the meaning is the broader one.

Be decisive, and lean towards true. Marking an inventory attribute as in-corpus
wastes one lookup; marking a real configuration parameter as out-of-corpus loses
the check entirely and returns nothing at all — measured on one Cisco payload,
that produced 0 retrievals from 5 items. When genuinely unsure, set is3gpp true."""


CM_PROMPT = """You are auditing a Bulk-CM configuration export against 3GPP specifications.

For EACH parameter you are given: its name, the expected (golden) value, the
configured (current) value, and EVIDENCE retrieved from the indexed specs for
that parameter specifically.

For each parameter output one line:
  <parameterName>: <verdict> — <one sentence> (TS xx.xxx)

Verdicts:
  SUPPORTED     the evidence states the allowed values/range and the current
                value violates it — say how.
  ALLOWED       the evidence shows the current value is permitted by the spec,
                so this is an operator policy deviation, not a spec violation.
  NOT IN SPECS  the evidence does not define this parameter. Say so plainly.
                Vendor/hardware attributes (serial numbers, antenna tilt, alarm
                text) are usually NOT 3GPP-defined — that is an expected result,
                not a failure.

Rules:
- Cite only spec ids present in that parameter's own evidence block.
- Each block is marked "parameter present in evidence: yes/no". That flag, not
  the confidence score, decides whether you have something to work with. A
  narrow IE lookup legitimately scores low because the retrieved clause covers
  several parameters — a low score with the parameter PRESENT is still usable
  evidence. Only report NOT IN SPECS when the flag says no.
- Never invent a spec number or a clause number.
- When a block has "PERMITTED VALUES", those ASN.1 lines are authoritative: compare
  the current value against them and decide. Several release variants may appear
  (-r13, -r14) with different lists — pick the one whose context matches the
  managed object (e.g. NB-IoT vs MBMS), and say which you used.
- NEVER state an allowed range or enumeration unless the literal values appear in
  that parameter's evidence. If the evidence names the parameter but does not
  show its permitted values, say exactly that: "defined in TS xx.xxx but the
  permitted values are not in the retrieved clause". Do NOT infer the range from
  the parameter's name, from a similar parameter, or from general knowledge — a
  fabricated range produces a confident, wrong compliance verdict, which is worse
  than reporting the gap.
- SUPPORTED requires the evidence to show values that the current value violates.
  Without those literal values you cannot claim a violation."""


PROCEDURE_PROMPT = """You are a 3GPP standards assistant assembling a call flow.

You are given EVIDENCE grouped by specification layer, retrieved from an offline
index. Each layer is a different part of the same procedure: stage-2 architecture
gives the flow, stage-3 gives the messages, radio layers give the air-interface legs.

Produce:
1. A numbered end-to-end message sequence. For EVERY step cite the spec it came
   from, e.g. "3. AMF -> SMF: Nsmf_PDUSession_CreateSMContext (TS 23.502)".
2. A short "Not covered" section naming any layer reported as having no strong
   evidence, stating plainly that the flow is incomplete there.

Rules:
- Use ONLY the evidence. Never add a step no passage supports.
- Never invent clause numbers. Cite only spec ids shown in the evidence.
- If the evidence does not support a coherent ordering, say so rather than guessing."""


# ── Grok (xAI) ───────────────────────────────────────────────────────────────

SYSTEM_PROMPT = """You are a 3GPP/telecom standards assistant.

You will be given a question and numbered EVIDENCE passages retrieved from an
offline index of 3GPP, ITU-T, IETF, ETSI and O-RAN specifications.

Rules:
- Answer ONLY from the evidence. Do not use outside knowledge to add facts.
- Cite the spec after every claim, e.g. "(TS 38.331)". Prefer the exact spec id
  shown in the evidence header.
- If the evidence does not answer the question, say so plainly and name which
  spec would normally hold the answer. Do not guess clause numbers.
- For procedure questions, give a numbered step-by-step flow, and mark which
  spec each step comes from.
- Be concise and technical. No preamble."""


# The scope gate emits this as the first line of the tool output when the spec
# that OWNS the topic is absent but related material is indexed. Parsed here for
# the same reason Confidence:/Intent: are — it is part of the tool's output
# format, and dropping it is what made the model refuse answerable halves.
COVERAGE_RE = re.compile(r"^(Partial coverage: .+?)(?:\n\s*\n|\Z)", re.S)


def coverage_note(raw):
    m = COVERAGE_RE.match((raw or "").lstrip())
    return " ".join(m.group(1).split()) if m else None


COVERAGE_RULE = """\n- A COVERAGE NOTE means the spec that OWNS the topic is not in the index, but
  related material IS. Do not refuse wholesale — that discards evidence that is
  genuinely on point. Instead:
    1. Answer whatever part the evidence DOES establish, cited as normal. A
       question about a specific technology's failure behaviour is usually still
       served by the generic mechanism the indexed spec defines.
    2. Then state, in one short paragraph, exactly which part you could NOT
       answer and which spec holds it.
  Split the answer under two headings: \"From indexed specs\" and \"Not indexed\"."""


def build_evidence(hits):
    parts = []
    for h in hits:
        parts.append(
            f"[{h['n']}] spec={h['spec_id']} release={h['release']} "
            f"title={h['title']}\n{h['excerpt']}")
    return "\n\n".join(parts)


def call_llm(provider, api_key, model, system, user, timeout=180, max_tokens=8000):
    """Plain system+user call — no evidence framing, no retrieval vocabulary."""
    cfg = PROVIDERS[provider]
    body = {"model": model or cfg["model"], "temperature": 0,
            "max_tokens": max_tokens,
            "messages": [{"role": "system", "content": system},
                         {"role": "user", "content": user}]}
    req = urllib.request.Request(
        cfg["url"], data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json",
                 "Authorization": f"Bearer {api_key}",
                 "User-Agent": "3gpp-mcp-ui/1.0"},
        method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode())
        return data["choices"][0]["message"]["content"], None
    except urllib.error.HTTPError as e:
        return None, f"{provider} HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:400]}"
    except Exception as e:  # noqa: BLE001
        return None, f"{provider} call failed: {e}"


def ask_llm(provider, api_key, model, question, hits, timeout=120, system=None,
            coverage=None):
    """Call an OpenAI-compatible chat endpoint. Returns (answer, error)."""
    cfg = PROVIDERS[provider]
    if not hits:
        return None, ("Retrieval returned 0 chunks, so there is nothing to ground "
                      "an answer in. Check the Series / Release / Doc type filters.")
    evidence = build_evidence(hits)
    prefix = f"COVERAGE NOTE:\n{coverage}\n\n" if coverage else ""
    body = {
        "model": model or cfg["model"],
        "temperature": 0.1,
        "messages": [
            {"role": "system",
             "content": (system or SYSTEM_PROMPT) + (COVERAGE_RULE if coverage else "")},
            {"role": "user",
             "content": f"QUESTION:\n{question}\n\n{prefix}EVIDENCE:\n{evidence}"},
        ],
    }
    req = urllib.request.Request(
        cfg["url"], data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json",
                 "Authorization": f"Bearer {api_key}",
                 # Groq sits behind Cloudflare, which rejects the default
                 # "Python-urllib/3.x" agent with a 1010 challenge before the
                 # request ever reaches the API.
                 "User-Agent": "3gpp-mcp-ui/1.0"},
        method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode())
        choice = data["choices"][0]
        content = choice["message"]["content"]
        # An oversized prompt comes back as HTTP 200 with content == "" and no
        # error, which the UI renders as a blank report. Log what the API actually
        # said so the next occurrence is diagnosable instead of silent.
        if not (content or "").strip():
            usage = data.get("usage") or {}
            sys.stderr.write(
                "[ui] EMPTY answer from %s/%s: finish_reason=%r prompt_tokens=%s "
                "completion_tokens=%s reasoning_chars=%s\n"
                % (provider, body["model"], choice.get("finish_reason"),
                   usage.get("prompt_tokens"), usage.get("completion_tokens"),
                   len(choice["message"].get("reasoning") or "")))
        return content, None
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")[:600]
        return None, f"{provider} HTTP {e.code} ({cfg['url']}): {detail}"
    except Exception as e:  # noqa: BLE001 - surfaced to the UI verbatim
        return None, f"{provider} call failed: {e}"


# One LLM request per N parameters, not one for the whole document.
# Measured on the 45-parameter Juniper payload: 26 evidence blocks concatenated
# to 180,774 chars (~45k tokens) in a single call, and the API returned HTTP 200
# with an empty string — no error, no report. CM_PROMPT emits one independent
# line per parameter, so the batches concatenate without a synthesis pass.
ANSWER_BATCH_CHARS = 40000


def answer_in_batches(provider, api_key, model, question, blocks, system,
                      budget=ANSWER_BATCH_CHARS):
    """Answer a per-item evidence list in prompt-sized batches.

    Returns (answer, error, n_batches). A batch that fails does not discard the
    others — its parameters are named in the error so nothing disappears
    silently, which is what the single oversized call did.
    """
    if not blocks:
        return None, "No evidence blocks to audit.", 0
    groups, cur, size = [], [], 0
    for b in blocks:
        if cur and size + len(b) > budget:
            groups.append(cur)
            cur, size = [], 0
        cur.append(b)
        size += len(b)
    if cur:
        groups.append(cur)

    parts, errors = [], []
    for i, grp in enumerate(groups, 1):
        ans, err = ask_llm(
            provider, api_key, model, question,
            [{"n": 1, "spec_id": "(per-parameter evidence)", "release": "",
              "title": "Bulk-CM audit", "excerpt": "\n\n".join(grp)}],
            system=system)
        if (ans or "").strip():
            parts.append(ans.strip())
        else:
            names = ", ".join(g.split("\n", 1)[0].lstrip("# ").split("  ")[0]
                              for g in grp)
            errors.append(f"batch {i}/{len(groups)} returned nothing"
                          + (f" ({err})" if err else "")
                          + f" — not audited: {names}")
    return ("\n".join(parts) or None,
            "; ".join(errors) or None,
            len(groups))


# The server splits search into two tools that share one description: a
# single-query one and a batch one. They are separate so the host can expose only
# the one a given deployment needs — with both shapes on a single tool, `query`
# and `queries` both had to be optional, which is a schema that also permits a
# call with neither.
#
# The UI does not care which is configured; it picks by the shape of the request.
SEARCH_TOOL = "search3gpp"
SEARCH_BATCH_TOOL = "search3gppBatch"


def search_tool_for(args):
    """Which server tool answers this call — batch iff `queries` is present."""
    return SEARCH_BATCH_TOOL if args.get("queries") else SEARCH_TOOL


def run_search(args):
    """Run a search through whichever tool this deployment actually exposes.

    Routing is by request SHAPE, not by asking a model: `queries` present means a
    batch, anything else is a single query. That is why /api/search and the
    config-audit path are deterministic while agent mode is not — there the model
    picks, which is what MCP_TOOLS_EXCLUDE is for.

    If the host has deconfigured the batch tool, the queries are run one at a time
    through the single-query tool and re-assembled into the same JSON map. The
    multi-value feature should not vanish because a deployment chose to expose one
    tool; it just costs N round trips instead of one.
    """
    batch = args.get("queries")
    if not batch:
        return mcp_call(SEARCH_TOOL, args)
    if tool_available(SEARCH_BATCH_TOOL):
        return mcp_call(SEARCH_BATCH_TOOL, args)

    single = {k: v for k, v in args.items() if k != "queries"}
    doc = {"_meta": {
        "queries": len(batch),
        "mode": "client-side loop",
        "mode_note": f"{SEARCH_BATCH_TOOL} is not exposed by this deployment, so each "
                     f"query was run separately through {SEARCH_TOOL}. Results are "
                     f"identical; only the round-trip count differs.",
    }}
    for q in batch:
        try:
            raw = mcp_call(SEARCH_TOOL, dict(single, query=q))
            # parse_hits emits the same keys the batch JSON carries, so the
            # assembled document is indistinguishable downstream.
            doc[q] = {"hits": parse_hits(raw)}
        except Exception as e:  # noqa: BLE001 - one failure must not lose the rest
            doc[q] = {"error": f"{type(e).__name__}: {e}"}
    return json.dumps(doc)


# ── HTTP handler ─────────────────────────────────────────────────────────────

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[ui] %s\n" % (fmt % args))

    def _send(self, code, payload, ctype="application/json"):
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        wire_new_rid()
        if self.path in ("/", "/index.html"):
            html = (HERE / "index.html").read_bytes()
            return self._send(200, html, "text/html; charset=utf-8")
        if self.path == "/api/health":
            try:
                stats = mcp_call("kbStats", {})
                return self._send(200, {"ok": True, "mcp": MCP_URL, "stats": stats})
            except Exception as e:  # noqa: BLE001
                return self._send(200, {"ok": False, "mcp": MCP_URL, "error": str(e)})
        if self.path.startswith("/api/tools"):
            tools, err = mcp_tools(force="refresh=1" in self.path)
            return self._send(200, {
                "mcp": MCP_URL, "error": err,
                "tools": [{"name": t.get("name"),
                           "description": (t.get("description") or "").strip(),
                           "parameters": sorted(
                               ((t.get("inputSchema") or {}).get("properties") or {}).keys()),
                           "required": (t.get("inputSchema") or {}).get("required") or []}
                          for t in tools],
            })
        return self._send(404, {"error": "not found"})

    def do_POST(self):
        wire_new_rid()
        length = int(self.headers.get("Content-Length", 0))
        try:
            payload = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            wire_log("ui<-browser", path=self.path, error="invalid JSON body")
            return self._send(400, {"error": "invalid JSON body"})
        # What the browser actually submitted, before any filter validation —
        # so a value dropped by _search_args() is still visible here.
        wire_log("ui<-browser", path=self.path, body=_redact(payload))

        if self.path == "/api/search":
            return self._handle_search(payload)
        if self.path == "/api/ask":
            return self._handle_ask(payload)
        if self.path == "/api/agent":
            return self._handle_agent(payload)
        if self.path == "/api/procedure":
            return self._handle_procedure(payload)
        if self.path == "/api/ie":
            return self._handle_ie(payload)
        if self.path == "/api/validate":
            return self._handle_validate(payload)
        if self.path == "/api/cmaudit":
            return self._handle_cm_audit(payload)
        if self.path == "/api/spec":
            return self._handle_spec(payload)
        return self._send(404, {"error": "not found"})

    # ── endpoints ────────────────────────────────────────────────────────────

    # Browsers happily autofill a field called "release" with things like
    # "admin". A junk filter silently reduces the search to zero hits, which
    # reads as "the index is broken" — so only pass through values that look
    # like real filters and report the rest back to the UI.
    RELEASE_RE = re.compile(r"^Rel-\d{1,2}$", re.I)
    # The non-3GPP half of the corpus is indexed under named series, and this
    # regex predates it: "^[0-9]{2}$|^[A-Z]$" silently dropped every one of them,
    # so a JUNIPER/CISCO/NOKIA filter typed in the UI did nothing while the index
    # held 89,000+ chunks under those names. Values below are the series column
    # of 3gpp_certified.db + telecom_extras.db verbatim.
    SERIES_RE = re.compile(
        r"^[0-9]{2}$|^[A-Z]$|"
        r"^(CISCO|JUNIPER|NOKIA|MIB|RFC|ETSI-NFV|ETSI NFV|ITU-T|ITU-T-G|"
        r"GSMA|O-RAN|TM-Forum|MEF)$", re.I)
    DOCTYPE_RE = re.compile(r"^(TS|TR|REC|RFC|GS|SPEC|CLI|YANG)$", re.I)

    def _search_args(self, p):
        args = {"query": p.get("query", ""), "topK": int(p.get("topK", 8) or 8)}
        ignored = []
        # Batch lookups. search3gpp gained a `queries` array so several
        # independent items — a list of alarm names, a set of parameters — can be
        # searched in one round trip instead of one call each. Pass it straight
        # through when the caller supplies it; the server does the loop and
        # labels the results per query. `query` is ignored server-side when
        # `queries` is present, so send only what was asked for.
        batch = p.get("queries")
        if isinstance(batch, str):                      # "a, b, c" from a form field
            batch = [s.strip() for s in batch.split(",")]
        if isinstance(batch, (list, tuple)):
            batch = [str(s).strip() for s in batch if str(s or "").strip()]
            if batch:
                args["queries"] = batch
                args.pop("query", None)
        checks = {"series": self.SERIES_RE, "release": self.RELEASE_RE,
                  "docType": self.DOCTYPE_RE}
        for key in ("series", "release", "docType", "verbosity"):
            val = (p.get(key) or "").strip()
            if not val:
                continue
            rule = checks.get(key)
            if rule and not rule.match(val):
                ignored.append(f"{key}={val!r}")
                continue
            args[key] = val
        return args, ignored

    @staticmethod
    def _normalise(p):
        """Unwrap a pasted request envelope so the inner question is what runs."""
        inner = unwrap_envelope(p.get("query"))
        if inner:
            p = {**p, "query": inner, "unwrapped_from_envelope": True}
        return p

    def _retrieve(self, p):
        """Search, then follow the SERVER's intent decision.

        There are no modes: the input decides. A pasted document goes to the
        extraction path; everything else is searched, and if the server's own
        intent classifier says "procedure" the cross-spec bundle is fetched too.
        The routing judgement stays server-side — the UI re-implementing it with
        its own keywords is exactly the hard-coding we are removing.

        Returns (payload, handled) — handled=True means a response was already sent.
        """
        if document_reason(p.get("query")):
            self._handle_document(p)
            return None, True

        args, ignored = self._search_args(p)
        t0 = time.time()
        try:
            raw = run_search(args)
        except Exception as e:  # noqa: BLE001
            self._send(502, {"error": str(e)})
            return None, True
        retrieval_ms = int((time.time() - t0) * 1000)

        layers = empty_layers = None
        intent = self.INTENT_RE.search(raw)
        if intent and intent.group(1).startswith("procedure"):
            try:
                praw = mcp_call("getProcedureFlow", {"procedure": p["query"]})
                layers, empty_layers = parse_procedure(praw)
                raw = raw + "\n\n" + praw
            except Exception:  # noqa: BLE001
                layers = None

        _hits = parse_hits(raw)
        _grouped = hits_by_query(raw, _hits)
        return {"hits": _hits,
                **({"hits_by_query": _grouped} if _grouped else {}),
                "raw": raw, "retrieval_ms": retrieval_ms,
                "ignored_filters": ignored, "layers": layers,
                "empty_layers": empty_layers,
                "unwrapped": bool(p.get("unwrapped_from_envelope")),
                "effective_query": p.get("query"),
                "coverage": coverage_note(raw)}, False

    def _handle_search(self, p):
        # Either form is valid: a single `query`, or a `queries` list for a batch.
        has_batch = bool(p.get("queries"))
        if not has_batch and not (p.get("query") or "").strip():
            return self._send(400, {"error": "query is required (or queries[] for a batch)"})
        p = self._normalise(p)
        out, handled = self._retrieve(p)
        if handled:
            return
        return self._send(200, out)

    def _handle_search_legacy(self, p):
        args, ignored = self._search_args(p)
        t0 = time.time()
        try:
            raw = run_search(args)
        except Exception as e:  # noqa: BLE001
            return self._send(502, {"error": str(e)})
        ms = int((time.time() - t0) * 1000)
        _hits2 = parse_hits(raw)
        _grouped2 = hits_by_query(raw, _hits2)
        return self._send(200, {"hits": _hits2,
                                **({"hits_by_query": _grouped2} if _grouped2 else {}),
                                "raw": raw,
                                "retrieval_ms": ms, "ignored_filters": ignored})

    INTENT_RE = re.compile(r"^Intent:\s*(\S+)", re.M)

    def _handle_ask(self, p):
        if not (p.get("query") or "").strip():
            return self._send(400, {"error": "query is required"})
        p = self._normalise(p)
        out, handled = self._retrieve(p)
        if handled:
            return

        api_key = (p.get("apiKey") or os.environ.get("LLM_API_KEY")
                   or os.environ.get("XAI_API_KEY") or "").strip()
        if not api_key:
            out["answer"] = None
            out["answer_error"] = ("No API key. Set LLM_API_KEY before starting the "
                                   "server, or paste a key in the key field.")
            return self._send(200, out)

        provider = resolve_provider((p.get("provider") or DEFAULT_PROVIDER), api_key)
        model = (p.get("model") or DEFAULT_MODEL or PROVIDERS[provider]["model"]).strip()
        note = out.get("coverage")
        t1 = time.time()
        if out.get("layers"):
            # Layer grouping is the signal that lets the model tell a stage-2 step
            # from a radio leg, so pass the grouped text rather than flat hits.
            answer, err = ask_llm(provider, api_key, model, p["query"],
                                  [{"n": 1, "spec_id": "(layered evidence)",
                                    "release": "", "title": p["query"],
                                    "excerpt": out["raw"]}], system=PROCEDURE_PROMPT,
                                  coverage=note)
        else:
            answer, err = ask_llm(provider, api_key, model, p["query"], out["hits"],
                                  coverage=note)
        out.update({"answer": answer, "answer_error": err, "provider": provider,
                    "model": model, "answer_ms": int((time.time() - t1) * 1000)})
        return self._send(200, out)

    def _handle_procedure(self, p):
        procedure = (p.get("query") or "").strip()
        if not procedure:
            return self._send(400, {"error": "procedure name is required"})
        args = {"procedure": procedure,
                "technology": (p.get("technology") or "5G").strip()}
        if p.get("perLayer"):
            args["perLayer"] = int(p["perLayer"])
        t0 = time.time()
        try:
            raw = mcp_call("getProcedureFlow", args)
        except Exception as e:  # noqa: BLE001
            return self._send(502, {"error": str(e)})
        retrieval_ms = int((time.time() - t0) * 1000)
        layers, empty = parse_procedure(raw)

        api_key = (p.get("apiKey") or os.environ.get("LLM_API_KEY")
                   or os.environ.get("XAI_API_KEY") or "").strip()
        out = {"layers": layers, "empty_layers": empty, "raw": raw,
               "retrieval_ms": retrieval_ms, "answer": None, "answer_error": None}
        if not api_key:
            out["answer_error"] = ("No API key — showing retrieved evidence only. "
                                   "Add a key to have the flow assembled.")
            return self._send(200, out)

        provider = resolve_provider((p.get("provider") or DEFAULT_PROVIDER), api_key)
        model = (p.get("model") or DEFAULT_MODEL or PROVIDERS[provider]["model"]).strip()
        # Feed the LAYERED text through, not a flattened hit list: the grouping is
        # the signal that lets the model tell a stage-2 step from a radio leg.
        t1 = time.time()
        answer, err = ask_llm(provider, api_key, model, procedure,
                              [{"n": 1, "spec_id": "(layered evidence)", "release": "",
                                "title": procedure, "excerpt": raw}],
                              system=PROCEDURE_PROMPT)
        out.update({"answer": answer, "answer_error": err, "provider": provider,
                    "model": model, "answer_ms": int((time.time() - t1) * 1000)})
        return self._send(200, out)

    def _handle_agent(self, p):
        """Hand the model the server's tools and let it decide what to call.

        No routing, no mode, no per-tool endpoint: the tool list comes from the
        MCP server at request time, so this path needs no edit when tools change.
        """
        question = (p.get("query") or "").strip()
        if not question:
            return self._send(400, {"error": "query is required"})

        # A CM audit payload goes to the deterministic path, not the tool loop.
        # Letting the model build the queries is measurably worse: given
        # {"parameterName":"input","moHierarchy":".../service/service-policy"} it
        # searches the bare word "input" and gets "show chassis power" at 0.27,
        # while mo_context() builds "input service service-policy" and gets the
        # actual Cisco service-policy/input node at 0.41. Same for "output"
        # (3GPP spectrum mask 0.32 vs the right Junos command 0.47) and "import"
        # (0.51 wrong vs 1.16 right). Telling the model to include the hierarchy
        # was tried and did not take — gpt-oss-20b kept sending bare names — so
        # the join is done here instead of asked for.
        # _handle_cm_audit also dedupes and drops parameters no spec defines,
        # which the tool loop cannot do: 5 items became 3 retrievals.
        if looks_like_cm_payload(question):
            return self._handle_cm_audit({**p, "payload": question,
                                          "auto_routed": True})

        api_key = (p.get("apiKey") or os.environ.get("LLM_API_KEY")
                   or os.environ.get("XAI_API_KEY") or "").strip()
        if not api_key:
            return self._send(200, {
                "hits": [], "raw": "", "retrieval_ms": 0, "trace": [], "answer": None,
                "answer_error": "Agent mode needs an API key — the model is the thing "
                                "choosing the tools. Paste a key or set LLM_API_KEY.",
            })
        tools, terr = mcp_tools()
        if not tools:
            return self._send(200, {
                "hits": [], "raw": "", "retrieval_ms": 0, "trace": [], "answer": None,
                "answer_error": f"No tools available from {MCP_URL}. {terr or ''}".strip(),
            })
        provider = resolve_provider((p.get("provider") or DEFAULT_PROVIDER), api_key)
        model = (p.get("model") or DEFAULT_MODEL or PROVIDERS[provider]["model"]).strip()

        t0 = time.time()
        answer, trace, err = run_agent(provider, api_key, model, question,
                                       openai_tools(tools))
        elapsed = int((time.time() - t0) * 1000)
        coverage = list_coverage(question, answer, trace)

        # Surface evidence from whichever calls returned chunks, so the Evidence
        # tab still works regardless of which tools the model chose.
        hits, raw_parts = [], []
        for step in trace:
            raw_parts.append(f"=== step {step['step']}: {step['tool']}("
                             + ", ".join(f"{k}={v!r}" for k, v in step["arguments"].items())
                             + f") — {step['ms']} ms ===\n{step['result']}")
            hits.extend(parse_hits(step["result"]))
        # The agent may make several tool calls; merge the per-query grouping from
        # every batch result so a four-parameter question comes back as four
        # labelled buckets, not one list the caller has to filter by hit["query"].
        grouped = {}
        for step in trace:
            g = hits_by_query(step.get("result") or "", parse_hits(step.get("result") or ""))
            if g:
                for q, block in g.items():
                    if q in grouped:
                        grouped[q]["hits"].extend(block["hits"])
                    else:
                        grouped[q] = block
        return self._send(200, {
            "hits": hits,
            **({"hits_by_query": grouped} if grouped else {}),
            "raw": "\n\n".join(raw_parts), "retrieval_ms": elapsed,
            "trace": trace, "tools_offered": [t.get("name") for t in tools],
            **({"coverage": coverage} if coverage else {}),
            "answer": answer, "answer_error": err, "answer_ms": elapsed,
            "provider": provider, "model": model,
        })

    def _route(self, p, text):
        """Question or document? Returns (kind, routed, error).

        kind is "question" or "document". `routed` carries the model's rewrite of
        a question ({"query": ..., "series": ...}) and is None for a document.

        Every failure path returns "question", because that is the safe default:
        a document sent down the question path still retrieves something usable,
        whereas a question sent down the document path returns an empty report.
        Without an API key we cannot ask, so the structural check is the only
        thing that can still divert a genuine Bulk-CM export.
        """
        api_key = (p.get("apiKey") or os.environ.get("LLM_API_KEY")
                   or os.environ.get("XAI_API_KEY") or "").strip()
        if not api_key:
            if looks_like_cm_payload(text):
                return "document", None, "no API key — routed by structure"
            return "question", None, None

        prov = resolve_provider((p.get("provider") or DEFAULT_PROVIDER), api_key)
        mdl = (p.get("model") or DEFAULT_MODEL or PROVIDERS[prov]["model"]).strip()
        raw, err = ask_llm(prov, api_key, mdl, "Route this input.",
                           [{"n": 1, "spec_id": "(input)", "release": "",
                             "title": "input", "excerpt": text}],
                           system=ROUTER_PROMPT, timeout=60)
        if err:
            return "question", None, f"router call failed: {err}"
        try:
            body = raw.strip()
            if body.startswith("```"):
                body = body.split("```")[1]
            obj = json.loads(body[body.find("{"):body.rfind("}") + 1])
        except (ValueError, IndexError) as e:
            return "question", None, f"router returned no usable JSON ({e})"
        if obj.get("kind") == "document":
            return "document", None, None
        return "question", {"query": (obj.get("query") or "").strip(),
                            "series": (obj.get("series") or "").strip()}, None

    def _extract_items(self, p, document):
        """Stage 1: let the model decide what is worth looking up.

        Prompt-driven rather than parsed in code, so it generalises to payload
        shapes nobody anticipated — a different vendor's export, XML, CSV, a
        pasted table. The code path below only ever sees the extracted items, so
        the document itself never reaches the retriever either way.
        """
        api_key = (p.get("apiKey") or os.environ.get("LLM_API_KEY")
                   or os.environ.get("XAI_API_KEY") or "").strip()
        if not api_key:
            return None, ("No API key, so the extraction prompt could not run. "
                          "Falling back to the built-in Bulk-CM parser.")
        prov = resolve_provider((p.get("provider") or DEFAULT_PROVIDER), api_key)
        mdl = (p.get("model") or DEFAULT_MODEL or PROVIDERS[prov]["model"]).strip()
        raw, err = call_llm(
            prov, api_key, mdl, EXTRACT_PROMPT,
            "Extract every configuration item from the document below.\n"
            "Return ONLY the JSON array described in your instructions.\n"
            "The document contains many items; do not return an empty array "
            "unless it genuinely contains no configuration items.\n\n"
            "--- BEGIN DOCUMENT ---\n" + document + "\n--- END DOCUMENT ---")
        if err:
            return None, f"extraction call failed: {err}"
        try:
            body = raw.strip()
            if body.startswith("```"):
                body = body.split("```")[1]
            items = json.loads(body[body.find("["):body.rfind("]") + 1])
        except (ValueError, IndexError) as e:
            return None, f"extraction did not return valid JSON ({e})"
        if not isinstance(items, list):
            return None, f"extraction did not return a JSON array (got {type(items).__name__})"

        # Be liberal about the key the model chose. Insisting on exactly
        # "parameter" silently produced an empty list — and an empty list was
        # then treated as a valid extraction, so the audit ran over 0 items and
        # the report said "no parameter data was supplied". Never again: an empty
        # result is a FAILURE here, not an answer.
        alias = ("parameter", "parameterName", "name", "param", "attribute")
        clean = []
        for i in items:
            if not isinstance(i, dict):
                continue
            key = next((k for k in alias if i.get(k)), None)
            if not key:
                continue
            row = dict(i)
            row["parameter"] = i[key]
            row.setdefault("ieName", i.get("ieName") or i.get("ie") or i.get("specName"))
            row.setdefault("expected", i.get("expected") or i.get("goldenValue"))
            row.setdefault("actual", i.get("actual") or i.get("currentValue"))
            row.setdefault("context", i.get("context") or i.get("moContext") or "")
            clean.append(row)

        if not clean:
            snippet = raw.strip().replace("\n", " ")[:220]
            if not items:
                return None, ("the extraction model returned an empty array for a "
                              f"{len(document):,}-character document. Reply was: {snippet}")
            return None, (f"extraction returned {len(items)} item(s) but none carried a "
                          f"parameter name. Model said: {snippet}")
        return clean, None

    def _handle_document(self, p):
        """Prompt-driven path: extract -> one short query per item -> report."""
        document = p.get("query") or ""
        items, extract_error = self._extract_items(p, document)
        if not items:
            # No key or a malformed extraction: use the structural parser when the
            # document is a shape we recognise, otherwise say why we stopped.
            if looks_like_cm_payload(document):
                return self._handle_cm_audit({**p, "payload": document,
                                              "auto_routed": True,
                                              "plan_error": extract_error})
            return self._send(200, {
                "hits": [], "raw": "", "retrieval_ms": 0, "answer": None,
                "answer_error": f"Could not extract lookups from the document. {extract_error}",
            })

        series_default = (p.get("series") or "").strip()
        t0, results = time.time(), []
        for it in items:
            if it.get("is3gpp") is False:
                results.append({
                    "parameter": it.get("parameter"),
                    "goldenValue": it.get("expected"), "currentValue": it.get("actual"),
                    "moContext": it.get("context") or "", "skipped": True,
                    "skip_reason": it.get("reason") or "not 3GPP-defined",
                    "confidence": "n/a", "hits": [], "param_present": False,
                    "has_definition": False, "definitions": "", "query": "",
                })
                continue
            ie = (it.get("ieName") or it.get("parameter") or "").strip()
            series = (it.get("series") or series_default).strip()
            query = " ".join(x for x in [ie, (it.get("context") or "").strip()] if x)
            args = {"query": query, "topK": 4, "verbosity": "full", "maxPerSpec": 3}
            if self.SERIES_RE.match(series or ""):
                args["series"] = series
            try:
                raw = run_search(args)
            except Exception as e:  # noqa: BLE001
                results.append({"parameter": it.get("parameter"), "query": query,
                                "error": str(e), "hits": [], "confidence": "error",
                                "param_present": False, "has_definition": False})
                continue
            hits = parse_hits(raw)
            defs_raw = ""
            try:
                if series:
                    defs_raw = mcp_call("lookupIeDefinition",
                                        {"ieName": ie, "series": series, "limit": 6})
            except Exception:  # noqa: BLE001
                defs_raw = ""
            has_defs = bool(defs_raw) and "No ASN.1 definition found" not in defs_raw
            conf = re.search(r"^Confidence:\s*(\w+)", raw, re.M)
            results.append({
                "parameter": it.get("parameter"), "ie_name": ie,
                "goldenValue": it.get("expected"), "currentValue": it.get("actual"),
                "moContext": it.get("context") or "", "query": query,
                "confidence": conf.group(1) if conf else "?",
                "param_present": param_is_present(it.get("parameter"), ie, hits),
                "has_definition": has_defs, "definitions": defs_raw if has_defs else "",
                "hits": hits, "raw": raw,
            })
        retrieval_ms = int((time.time() - t0) * 1000)
        queried = sum(1 for r in results if not r.get("skipped"))
        out = {"results": results, "queried": queried,
               "planned_out": len(results) - queried,
               "total_parameters": len(items), "unique_parameters": len(items),
               "skipped": 0, "series": series_default or None,
               "extracted_by": "prompt", "auto_routed": True,
               "plan_error": extract_error, "retrieval_ms": retrieval_ms,
               "answer": None, "answer_error": None}

        api_key = (p.get("apiKey") or os.environ.get("LLM_API_KEY")
                   or os.environ.get("XAI_API_KEY") or "").strip()
        if api_key:
            prov = resolve_provider((p.get("provider") or DEFAULT_PROVIDER), api_key)
            mdl = (p.get("model") or DEFAULT_MODEL or PROVIDERS[prov]["model"]).strip()
            blocks = []
            for r in results:
                if r.get("skipped"):
                    continue
                ev = "\n".join(f"  - {h['spec_id']}: {h['excerpt'][:2500]}"
                                for h in r.get("hits", []))
                if r.get("definitions"):
                    ev = ("  PERMITTED VALUES (verbatim ASN.1 — use THESE):\n"
                          + "\n".join("    " + ln for ln in r["definitions"].splitlines()
                                       if ln.strip() and not ln.startswith("These are"))
                          + "\n  Context:\n" + ev)
                blocks.append(f"### {r['parameter']} (expected={r.get('goldenValue')}, "
                              f"actual={r.get('currentValue')}, parameter present: "
                              f"{'yes' if r.get('param_present') else 'no'}, "
                              f"permitted values retrieved: "
                              f"{'yes' if r.get('has_definition') else 'no'})\n"
                              + (ev or "  (no evidence retrieved)"))
            t1 = time.time()
            ans, err = ask_llm(prov, api_key, mdl, "Audit these parameters.",
                               [{"n": 1, "spec_id": "(per-parameter evidence)",
                                 "release": "", "title": "audit",
                                 "excerpt": "\n\n".join(blocks)}], system=CM_PROMPT)
            out.update({"answer": ans, "answer_error": err, "provider": prov,
                        "model": mdl, "answer_ms": int((time.time() - t1) * 1000)})
        return self._send(200, out)

    def _handle_cm_audit(self, p):
        raw_payload = p.get("payload")
        if isinstance(raw_payload, str):
            try:
                raw_payload = json.loads(raw_payload)
            except json.JSONDecodeError as e:
                return self._send(400, {"error": f"payload is not valid JSON: {e}"})
        if not isinstance(raw_payload, dict):
            return self._send(400, {"error": "payload must be a JSON object"})

        data = raw_payload.get("data", raw_payload)
        items = data.get("nonCompliancedata") or []
        if not items:
            return self._send(400, {"error": "no nonCompliancedata[] entries found"})

        tech = str(data.get("technology") or "").lower()
        vendor = str(data.get("vendor") or "").lower()
        # An explicit series in the request wins; then the radio technology; then
        # the vendor's own model. Without the last one a TRANSPORT/COMMON payload
        # searched the whole corpus with two-word queries — see VENDOR_SERIES.
        series = ((p.get("series") or "").strip()
                  or TECH_SERIES.get(tech, "")
                  or VENDOR_SERIES.get(vendor, ""))
        limit = int(p.get("maxParams") or (30 if p.get("auto_routed") else 20))

        # De-duplicate: the same parameter often appears under several managed
        # objects, and re-querying it wastes seconds for an identical answer.
        seen, unique = set(), []
        for it in items:
            key = (it.get("parameterName"), mo_context(it.get("moHierarchy"), it.get("parameterName")))
            if key in seen:
                continue
            seen.add(key)
            unique.append(it)
        skipped = max(0, len(unique) - limit)
        todo = unique[:limit]

        # ── Planning pass ────────────────────────────────────────────────
        # Ask the model, ONCE, which of these are actually 3GPP-defined and what
        # the spec calls them. Without this every hardware attribute burns a
        # retrieval round-trip and the static alias table has to know every
        # vendor spelling in advance.
        api_key_plan = (p.get("apiKey") or os.environ.get("LLM_API_KEY")
                        or os.environ.get("XAI_API_KEY") or "").strip()
        plan = {}
        plan_error = p.get("plan_error")
        if api_key_plan and p.get("plan", True):
            prov = resolve_provider((p.get("provider") or DEFAULT_PROVIDER), api_key_plan)
            mdl = (p.get("model") or DEFAULT_MODEL or PROVIDERS[prov]["model"]).strip()
            listing = "\n".join(
                f'{i+1}. {x.get("parameterName")}  @ {mo_context(x.get("moHierarchy"), x.get("parameterName"))}'
                for i, x in enumerate(todo))
            raw_plan, plan_error = ask_llm(
                prov, api_key_plan, mdl, "Triage these attributes.",
                [{"n": 1, "spec_id": "(attribute list)", "release": "",
                  "title": "Bulk-CM attributes", "excerpt": listing}],
                system=PLAN_PROMPT)
            if raw_plan:
                try:
                    body = raw_plan.strip()
                    if body.startswith("```"):
                        body = body.split("```")[1]
                        body = body[body.find("["):]
                    parsed = json.loads(body[body.find("["):body.rfind("]") + 1])
                    for row in parsed:
                        if isinstance(row, dict) and row.get("parameter"):
                            plan[row["parameter"]] = row
                except (ValueError, IndexError) as e:
                    plan_error = f"plan response was not valid JSON ({e}); using the static alias table"

        t0 = time.time()
        results = []
        for it in todo:
            pl = plan.get(it.get("parameterName")) or {}
            # Skip only what the model positively called non-3GPP. A missing plan
            # entry means "no opinion", which must not silently drop a parameter.
            if pl.get("is3gpp") is False:
                results.append({
                    "parameter": it.get("parameterName"),
                    "goldenValue": it.get("goldenValue"),
                    "currentValue": it.get("currentValue"),
                    "moContext": mo_context(it.get("moHierarchy"), it.get("parameterName")),
                    "skipped": True,
                    "skip_reason": pl.get("reason") or "not 3GPP-defined",
                    "confidence": "n/a", "hits": [], "param_present": False,
                    "has_definition": False, "definitions": "", "query": "",
                })
                continue
            query = build_param_query(it, pl.get("ieName"))
            # maxPerSpec=3: the definition of an IE often sits in an ASN.1 block
            # that the cross-encoder ranks below prose mentioning the same IE. With
            # the default cap of 1 chunk per spec the definition is unreachable.
            args = {"query": query, "topK": 4, "verbosity": "full", "maxPerSpec": 3}
            if series:
                args["series"] = series
            try:
                raw = run_search(args)
            except Exception as e:  # noqa: BLE001
                results.append({"parameter": it.get("parameterName"), "query": query,
                                "error": str(e), "hits": [], "confidence": "error"})
                continue
            conf = re.search(r"^Confidence:\s*(\w+)", raw, re.M)
            pname = it.get("parameterName")
            hits = parse_hits(raw)
            # Exact ASN.1 lookup for the permitted values. Semantic search finds
            # the spec; only this finds the enumeration that decides compliance.
            ie = (pl.get("ieName") or "").strip() or spec_term(pname)
            # ASN.1 definitions are a 3GPP construct — the clause index only holds
            # them for numeric series. Calling this with series="JUNIPER" would add
            # one round trip per parameter for a guaranteed empty result.
            try:
                defs_raw = mcp_call("lookupIeDefinition",
                                    {"ieName": ie, "series": series, "limit": 6}
                                    ) if series.isdigit() else ""
            except Exception:  # noqa: BLE001
                defs_raw = ""
            has_defs = bool(defs_raw) and "No ASN.1 definition found" not in defs_raw
            results.append({
                "parameter": pname,
                "ie_name": ie,
                "param_present": param_is_present(pname, ie, hits),
                "has_definition": has_defs,
                "definitions": defs_raw if has_defs else "",
                "goldenValue": it.get("goldenValue"),
                "currentValue": it.get("currentValue"),
                "moContext": mo_context(it.get("moHierarchy"), it.get("parameterName")),
                "query": query,
                "confidence": conf.group(1) if conf else "?",
                "hits": hits,
                "raw": raw,
            })
        retrieval_ms = int((time.time() - t0) * 1000)

        queried = sum(1 for r in results if not r.get("skipped"))
        out = {"results": results, "series": series or None, "technology": tech or None,
               "total_parameters": len(items), "unique_parameters": len(unique),
               "queried": queried, "planned_out": len(todo) - queried,
               "plan_error": plan_error, "skipped": skipped, "retrieval_ms": retrieval_ms,
               "auto_routed": bool(p.get("auto_routed")),
               "answer": None, "answer_error": None}

        api_key = (p.get("apiKey") or os.environ.get("LLM_API_KEY")
                   or os.environ.get("XAI_API_KEY") or "").strip()
        if not api_key:
            out["answer_error"] = ("No API key — showing per-parameter evidence only. "
                                   "Add a key to have the compliance report written.")
            return self._send(200, out)

        provider = resolve_provider((p.get("provider") or DEFAULT_PROVIDER), api_key)
        model = (p.get("model") or DEFAULT_MODEL or PROVIDERS[provider]["model"]).strip()
        blocks = []
        for r in results:
            # No 600-char truncation here. The enumeration that decides compliance
            # ("ENUMERATED {t1, t2, ... t32}") frequently sits past that cut, and a
            # model given a definition with the values chopped off invents them.
            ev = "\n".join(f"  - {h['spec_id']}: {h['excerpt'][:2500]}" for h in r.get("hits", []))
            if r.get("definitions"):
                ev = ("  PERMITTED VALUES (verbatim ASN.1 from the spec — use THESE):\n"
                      + "\n".join("    " + ln for ln in r["definitions"].splitlines()
                                   if ln.strip() and not ln.startswith("These are"))
                      + "\n  Surrounding context:\n" + ev)
            blocks.append(f"### {r['parameter']}  (golden={r.get('goldenValue')}, "
                          f"current={r.get('currentValue')}, "
                          f"parameter present: {'yes' if r.get('param_present') else 'no'}, "
                          f"permitted values retrieved: "
                          f"{'yes' if r.get('has_definition') else 'no'})\n"
                          f"{ev or '  (no evidence retrieved)'}")
        t1 = time.time()
        answer, err, batches = answer_in_batches(
            provider, api_key, model, "Audit these parameters against the specs.",
            blocks, CM_PROMPT)
        out.update({"answer": answer, "answer_error": err, "provider": provider,
                    "model": model, "answer_batches": batches,
                    "answer_ms": int((time.time() - t1) * 1000)})
        return self._send(200, out)

    def _handle_ie(self, p):
        """Direct IE definition lookup — the clause-index path, no LLM involved."""
        ie = (p.get("query") or "").strip()
        if not ie:
            return self._send(400, {"error": "an information-element name is required"})
        args = {"ieName": ie, "limit": int(p.get("limit", 8))}
        series = (p.get("series") or "").strip()
        if series and self.SERIES_RE.match(series):
            args["series"] = series
        t0 = time.time()
        try:
            raw = mcp_call("lookupIeDefinition", args)
        except Exception as e:  # noqa: BLE001
            return self._send(502, {"error": str(e)})
        found = "No ASN.1 definition found" not in raw
        defs = []
        for m in re.finditer(r"^(\S+)\s+\|\s+(\S+)\s+\|\s+(\S+)$\n\s+(.+)$", raw, re.M):
            defs.append({"spec_id": m.group(1), "release": m.group(2),
                         "ie_name": m.group(3), "definition": m.group(4)})
        return self._send(200, {"ie": ie, "found": found, "definitions": defs,
                                "raw": raw, "retrieval_ms": int((time.time() - t0) * 1000)})

    def _handle_validate(self, p):
        question = (p.get("query") or "").strip()
        draft = (p.get("draft") or "").strip()
        if not question or not draft:
            return self._send(400, {"error": "query and draft are both required"})
        try:
            raw = mcp_call("validateAnswer", {"question": question, "draftAnswer": draft})
        except Exception as e:  # noqa: BLE001
            return self._send(502, {"error": str(e)})
        return self._send(200, {"raw": raw})

    def _handle_spec(self, p):
        spec_id = (p.get("specId") or "").strip()
        if not spec_id:
            return self._send(400, {"error": "specId is required"})
        try:
            raw = mcp_call("getSpecInfo", {
                "specId": spec_id,
                "maxChunks": int(p.get("maxChunks", 5)),
            })
        except Exception as e:  # noqa: BLE001
            return self._send(502, {"error": str(e)})
        return self._send(200, {"raw": raw})


def main():
    global MCP_URL, DEFAULT_PROVIDER, DEFAULT_MODEL
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--mcp", default=MCP_URL, help="MCP endpoint of the Spring server")
    ap.add_argument("--provider", default=DEFAULT_PROVIDER,
                    choices=["auto", *PROVIDERS],
                    help="LLM provider (auto = infer from the key prefix)")
    ap.add_argument("--model", default=DEFAULT_MODEL,
                    help="model id (blank = provider default)")
    args = ap.parse_args()
    MCP_URL = args.mcp
    DEFAULT_PROVIDER = args.provider
    DEFAULT_MODEL = args.model

    key = os.environ.get("LLM_API_KEY") or os.environ.get("XAI_API_KEY") or ""
    srv = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"3GPP test UI  →  http://localhost:{args.port}")
    print(f"  MCP backend : {MCP_URL}")
    print(f"  provider    : {args.provider}"
          + (f"  (key prefix looks like {resolve_provider('auto', key)})" if key else ""))
    print(f"  model       : {args.model or 'provider default'}")
    print(f"  API key     : {'set in env' if key else 'not set (paste it in the UI)'}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nshutting down")
        srv.shutdown()


if __name__ == "__main__":
    main()

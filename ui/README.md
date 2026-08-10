# Local test console

A zero-dependency browser UI for hand-testing the 3GPP MCP server, with an
optional Grok (xAI) step that turns retrieved chunks into a grounded, cited answer.

```
browser  →  ui/server.py (127.0.0.1:8080)  →  MCP server (:3000)  →  SQLite + BGE-M3 + reranker
                                           ↘  api.x.ai  (only when a key is supplied)
```

## Run it

1. Start the backend (takes ~90 s — it loads 196k vectors and the reranker):

   ```bash
   ./start.sh dev
   ```

2. Start the UI:

   ```bash
   export XAI_API_KEY=xai-...        # optional — you can also paste it in the UI
   python3 ui/server.py              # → http://localhost:8080
   ```

   Options: `--port 8090`, `--mcp http://host:3000/mcp`, `--model grok-4`.

3. Open <http://localhost:8080>.

## Using it

- **Retrieve + answer with Grok** — runs `search3gpp`, then sends the retrieved
  chunks to Grok with a strict "answer only from this evidence, cite the spec"
  prompt. Cmd/Ctrl+Enter in the question box does the same.
- **Retrieve only** — pure retrieval, no LLM call and no key needed. Use this
  when you're evaluating the retriever itself.
- **Series / Release / Doc type** filters map straight onto the MCP tool params.
  `Doc type = TS` is a quick way to exclude study reports (TRs).
- **Raw MCP output** tab shows exactly what the tool returned, which is what an
  MCP client such as Claude would see.

## About the key

The key is held in the browser's `localStorage` and posted to the local proxy
on `127.0.0.1` only. The proxy adds it as a Bearer header on the call to
`api.x.ai` and never logs or persists it. Setting `XAI_API_KEY` in the
environment instead keeps it out of the browser entirely.

If your key isn't provisioned for `grok-4`, change the model field (or pass
`--model`) — the proxy surfaces the xAI HTTP error verbatim so a bad model id
is obvious.

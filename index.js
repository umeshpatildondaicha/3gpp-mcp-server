#!/usr/bin/env node
/**
 * 3GPP Telecom KB — Pure HTTP/SSE MCP Server v1.0.0
 * ==================================================
 * Always runs as an HTTP service. No stdio. No local DB on client.
 *
 * Endpoints:
 *   GET  /sse            → SSE stream (Claude Desktop, Cursor, legacy clients)
 *   POST /message        → SSE message handler (?sessionId=xxx)
 *   POST /mcp            → Streamable HTTP (modern web clients)
 *   GET  /health         → health + stats JSON
 *
 * Config (env vars):
 *   PORT=3000
 *   KB_DB_PATH=/path/to/3gpp.db     local file
 *   SEAWEEDFS_URL=http://filer/path  SeaweedFS filer HTTP direct URL
 *   KB_DB_URL=https://...            any HTTP/HTTPS download URL
 *   S3_ENDPOINT, S3_BUCKET, S3_KEY, S3_USER, S3_PASS, S3_REGION
 */

import { McpServer }                     from "@modelcontextprotocol/sdk/server/mcp.js";
import { SSEServerTransport }            from "@modelcontextprotocol/sdk/server/sse.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { z }                             from "zod";
import { existsSync, mkdirSync, createWriteStream, readFileSync } from "fs";
import { homedir }                       from "os";
import { join, dirname }                 from "path";
import { createServer }                  from "http";
import { randomUUID }                    from "crypto";
import { createRequire }                 from "module";

const _require = createRequire(import.meta.url);

// ── Config ──────────────────────────────────────────────────────────────────
const PORT = Number(process.env.PORT) || 3000;
const S3_REGION = (
  process.env.S3_REGION ||
  process.env.AWS_REGION ||
  process.env.AWS_DEFAULT_REGION ||
  "us-east-1"
);

const DB_ARG = (
  process.env.KB_DB_PATH    ??
  process.env.SEAWEEDFS_URL ??
  process.env.KB_DB_URL     ??
  null
);

function isUrl(s)    { return s && (s.startsWith("http://") || s.startsWith("https://")); }
function hasS3Cfg()  {
  return Boolean(
    process.env.S3_USER     &&
    process.env.S3_PASS     &&
    process.env.S3_BUCKET   &&
    process.env.S3_ENDPOINT
  );
}

function log(msg)  { process.stderr.write(`[3gpp-mcp] ${msg}\n`); }
function die(msg)  { log(`ERROR — ${msg}`); process.exit(1); }

// ── DB download helpers ──────────────────────────────────────────────────────
async function streamToFile(body, dest, totalBytes) {
  const writer = createWriteStream(dest);
  let done = 0, lastPct = -1;

  const tick = (n) => {
    done += n;
    if (totalBytes > 0) {
      const pct = Math.floor(done / totalBytes * 100);
      if (pct !== lastPct && pct % 10 === 0) {
        log(`downloading… ${pct}% (${(done / 1e6).toFixed(0)} MB)`);
        lastPct = pct;
      }
    }
  };

  if (typeof body?.getReader === "function") {
    const reader = body.getReader();
    try {
      while (true) {
        const { done: eof, value } = await reader.read();
        if (eof) break;
        writer.write(Buffer.from(value));
        tick(value.length);
      }
      await new Promise((res, rej) => writer.end(err => err ? rej(err) : res()));
    } catch (e) { writer.destroy(); throw e; }
  } else {
    await new Promise((resolve, reject) => {
      body.on("data", chunk => { tick(chunk.length); });
      body.on("error", reject);
      writer.on("error", reject);
      writer.on("finish", resolve);
      body.pipe(writer);
    });
  }
  return done;
}

async function downloadHttp(url, dest) {
  const resp = await fetch(url, { redirect: "follow" });
  if (!resp.ok) die(`HTTP ${resp.status} downloading ${url}`);
  const total = Number(resp.headers.get("content-length") || 0);
  return streamToFile(resp.body, dest, total);
}

async function downloadS3(dest) {
  const { S3Client, GetObjectCommand } = await import("@aws-sdk/client-s3");
  const client = new S3Client({
    region:        S3_REGION,
    endpoint:      process.env.S3_ENDPOINT,
    forcePathStyle: true,
    credentials: {
      accessKeyId:     process.env.S3_USER,
      secretAccessKey: process.env.S3_PASS,
    },
  });
  const key    = process.env.S3_KEY || "3gpp.db";
  const bucket = process.env.S3_BUCKET;
  log(`downloading s3://${bucket}/${key} via ${process.env.S3_ENDPOINT}`);
  const out = await client.send(new GetObjectCommand({ Bucket: bucket, Key: key }));
  return streamToFile(out.Body, dest, Number(out.ContentLength || 0));
}

// ── Resolve DB path (download + cache if needed) ────────────────────────────
async function resolveDb() {
  const cacheDir  = join(homedir(), ".3gpp-kb");
  const cachePath = join(cacheDir, "3gpp.db");

  const useCache = () => {
    log(`using cached DB → ${cachePath}`);
    return cachePath;
  };

  // No arg → try S3 env vars, then well-known local paths
  if (!DB_ARG) {
    if (hasS3Cfg()) {
      if (existsSync(cachePath)) return useCache();
      mkdirSync(cacheDir, { recursive: true });
      const bytes = await downloadS3(cachePath).catch(e => {
        try { require("fs").unlinkSync(cachePath); } catch {}
        die(`S3 download failed: ${e.message}`);
      });
      log(`download complete — ${(bytes / 1e6).toFixed(0)} MB → ${cachePath}`);
      return cachePath;
    }
    for (const p of [join(process.cwd(), "3gpp.db"), cachePath])
      if (existsSync(p)) return p;
    return null;
  }

  // Explicit local path
  if (!isUrl(DB_ARG)) {
    if (!existsSync(DB_ARG)) die(`DB file not found: ${DB_ARG}`);
    return DB_ARG;
  }

  // HTTP/HTTPS URL (SeaweedFS filer, HuggingFace, etc.)
  if (existsSync(cachePath)) return useCache();
  log(`downloading DB from ${DB_ARG}`);
  mkdirSync(cacheDir, { recursive: true });
  const bytes = await downloadHttp(DB_ARG, cachePath).catch(e => {
    try { require("fs").unlinkSync(cachePath); } catch {}
    die(`download failed: ${e.message}`);
  });
  log(`download complete — ${(bytes / 1e6).toFixed(0)} MB → ${cachePath}`);
  return cachePath;
}

// ── Database (sql.js WASM — zero native compilation) ────────────────────────
let db;
const DIM = 384;
let allEmbeddings, chunkIds, chunkMeta;

function wrapDb(sqlDb) {
  return {
    prepare(sql) {
      return {
        all(...params) {
          const stmt = sqlDb.prepare(sql);
          if (params.length) stmt.bind(params);
          const rows = [];
          while (stmt.step()) rows.push(stmt.getAsObject());
          stmt.free();
          return rows;
        },
        get(...params) {
          const stmt = sqlDb.prepare(sql);
          if (params.length) stmt.bind(params);
          const row = stmt.step() ? stmt.getAsObject() : undefined;
          stmt.free();
          return row;
        },
      };
    },
  };
}

async function initDb(dbPath) {
  const { default: initSqlJs } = await import("sql.js");
  const wasmPath = join(dirname(_require.resolve("sql.js")), "sql-wasm.wasm");
  const SQL      = await initSqlJs({ locateFile: () => wasmPath });

  log("opening DB…");
  const buf = readFileSync(dbPath);
  db = wrapDb(new SQL.Database(buf));

  log("loading embeddings into memory…");
  const rows = db.prepare("SELECT chunk_id, vector FROM embeddings ORDER BY rowid").all();
  const n    = rows.length;
  allEmbeddings = new Float32Array(n * DIM);
  chunkIds      = new Array(n);

  rows.forEach((row, i) => {
    chunkIds[i] = row.chunk_id;
    const src   = new Float32Array(row.vector.buffer, row.vector.byteOffset, DIM);
    allEmbeddings.set(src, i * DIM);
  });

  chunkMeta = new Map();
  db.prepare("SELECT id, spec_id, release, series, series_desc, doc_type, title FROM chunks")
    .all()
    .forEach(r => chunkMeta.set(r.id, r));

  log(`${n.toLocaleString()} vectors ready (${(n * DIM * 4 / 1e6).toFixed(0)} MB)`);
}

// ── Embedding model ──────────────────────────────────────────────────────────
let embedder;

async function initEmbedder() {
  log("loading embedding model (first run ~25 MB download)…");
  const { pipeline, env } = await import("@huggingface/transformers");
  env.allowLocalModels    = false;
  embedder = await pipeline("feature-extraction", "Xenova/all-MiniLM-L6-v2");
  log("embedding model ready.");
}

async function embed(text) {
  const out = await embedder(text, { pooling: "mean", normalize: true });
  return new Float32Array(out.data);
}

// ── Search ───────────────────────────────────────────────────────────────────
function cosineTopK(qvec, k, { series, release, doc_type }) {
  const n = chunkIds.length;
  const results = [];
  for (let i = 0; i < n; i++) {
    const meta = chunkMeta.get(chunkIds[i]);
    if (!meta) continue;
    if (series   && meta.series   !== series)   continue;
    if (release  && meta.release  !== release)  continue;
    if (doc_type && meta.doc_type !== doc_type) continue;
    const off = i * DIM;
    let dot = 0;
    for (let j = 0; j < DIM; j++) dot += qvec[j] * allEmbeddings[off + j];
    results.push({ id: chunkIds[i], score: dot });
  }
  results.sort((a, b) => b.score - a.score);
  return results.slice(0, k);
}

function search(qvec, topK, filters) {
  return cosineTopK(qvec, topK, filters).map(h => {
    const meta = chunkMeta.get(h.id);
    const row  = db.prepare("SELECT text FROM chunks WHERE id=?").get(h.id);
    const text = row?.text ?? "";
    return {
      score:       Math.round(h.score * 10000) / 10000,
      spec_id:     meta.spec_id,
      release:     meta.release,
      title:       meta.title,
      series_desc: meta.series_desc,
      snippet:     text.slice(0, 400) + (text.length > 400 ? "…" : ""),
    };
  });
}

function formatHits(hits, query) {
  if (!hits?.length) return "No results found. Try broadening the query or removing filters.";
  const lines = [`Search results for: "${query}"`, `(${hits.length} results)\n`];
  hits.forEach((h, i) => {
    lines.push(`[${i + 1}] ${h.spec_id} | ${h.release} | Score: ${h.score}`);
    lines.push(`    Title  : ${h.title}`);
    lines.push(`    Series : ${h.series_desc}`);
    lines.push(`    Excerpt: ${h.snippet}`);
    lines.push("");
  });
  return lines.join("\n");
}

const SERIES_MAP = {
  "21":"Vocabulary, Requirements","22":"Service Aspects & Stage 1",
  "23":"Architecture & Stage 2","24":"Signalling & Stage 3 (UE-Network)",
  "25":"UTRAN / WCDMA Radio Access","26":"Codecs & Media","27":"Data",
  "28":"Telecom Management (OAM)","29":"Core Network Protocols",
  "31":"SIM / USIM","32":"OAM & Charging","33":"Security",
  "34":"Test Specifications","35":"Security Algorithms",
  "36":"LTE / E-UTRAN (4G)","37":"Multi-RAT / Co-existence",
  "38":"NR / 5G Radio Access","45":"GSM / EDGE Radio Access",
};

// ── MCP server factory (one instance per SSE session) ───────────────────────
function createMcpServer() {
  const server = new McpServer({ name: "3gpp-telecom-kb", version: "1.0.0" });

  server.tool(
    "search_3gpp",
    "Semantic search across all indexed 3GPP specifications. " +
      "Filters: series ('38'=5G NR, '36'=LTE, '23'=Arch, '29'=Core, '33'=Security), " +
      "release ('Rel-17','Rel-18','Rel-19'), doc_type ('TS'/'TR').",
    {
      query:    z.string().describe("Natural-language question"),
      top_k:    z.number().int().min(1).max(50).optional().default(10),
      series:   z.string().optional(),
      release:  z.string().optional(),
      doc_type: z.enum(["TS","TR"]).optional(),
    },
    async ({ query, top_k, series, release, doc_type }) => {
      try {
        const qvec = await embed(query);
        const hits = search(qvec, top_k, { series, release, doc_type });
        return { content: [{ type: "text", text: formatHits(hits, query) }] };
      } catch (e) {
        return { content: [{ type: "text", text: `ERROR: ${e.message}` }] };
      }
    }
  );

  server.tool(
    "get_spec_info",
    "Retrieve text of a specific 3GPP spec by ID (e.g. '38.331', '23.501').",
    {
      spec_id:    z.string(),
      max_chunks: z.number().int().min(1).max(20).optional().default(5),
    },
    async ({ spec_id, max_chunks }) => {
      try {
        const rows = db
          .prepare("SELECT * FROM chunks WHERE spec_id=? ORDER BY chunk_index LIMIT ?")
          .all(spec_id, max_chunks);
        if (!rows.length)
          return { content: [{ type: "text", text: `Spec '${spec_id}' not found. Use list_specs().` }] };
        const m = rows[0];
        const lines = [
          `=== 3GPP ${m.doc_type} ${m.spec_id} ===`,
          `Title   : ${m.title}`,
          `Release : ${m.release}`,
          `Series  : ${m.series} — ${m.series_desc}`,
          `Chunks  : showing ${rows.length} of ${m.total_chunks} total\n`,
        ];
        rows.forEach(r => {
          lines.push(`--- Chunk ${r.chunk_index + 1}/${m.total_chunks} ---`);
          lines.push(r.text, "");
        });
        return { content: [{ type: "text", text: lines.join("\n") }] };
      } catch (e) {
        return { content: [{ type: "text", text: `ERROR: ${e.message}` }] };
      }
    }
  );

  server.tool(
    "list_specs",
    "List all 3GPP specs in the knowledge base.",
    {
      series:  z.string().optional(),
      release: z.string().optional(),
    },
    async ({ series, release }) => {
      try {
        let sql = `SELECT spec_id, series, series_desc, release, doc_type,
                          MAX(total_chunks) AS total_chunks FROM chunks`;
        const where = [], params = [];
        if (series)  { where.push("series=?");  params.push(series);  }
        if (release) { where.push("release=?"); params.push(release); }
        if (where.length) sql += " WHERE " + where.join(" AND ");
        sql += " GROUP BY spec_id ORDER BY spec_id";
        const specs = db.prepare(sql).all(...params);
        if (!specs?.length)
          return { content: [{ type: "text", text: "No specs found." }] };
        const header = [
          `Indexed 3GPP specs (${specs.length} total)\n`,
          `${"Spec ID".padEnd(14)} ${"Type".padEnd(5)} ${"Release".padEnd(10)} ${"Chunks".padEnd(8)} Series`,
          "-".repeat(70),
        ];
        return { content: [{ type: "text", text: [
          ...header,
          ...specs.map(s =>
            `${s.spec_id.padEnd(14)} ${s.doc_type.padEnd(5)} ${s.release.padEnd(10)} ${String(s.total_chunks).padEnd(8)} ${s.series_desc}`
          ),
        ].join("\n") }] };
      } catch (e) {
        return { content: [{ type: "text", text: `ERROR: ${e.message}` }] };
      }
    }
  );

  server.tool(
    "list_series",
    "3GPP series catalog with descriptions and which are indexed.",
    {},
    async () => {
      try {
        const indexed = new Set(
          db.prepare("SELECT DISTINCT series FROM chunks").all().map(r => r.series)
        );
        const lines = ["3GPP Series Catalog\n",
          `${"Series".padEnd(8)} ${"Indexed".padEnd(9)} Description`, "-".repeat(55)];
        for (const [num, desc] of Object.entries(SERIES_MAP).sort(([a],[b]) => +a - +b))
          lines.push(`${num.padEnd(8)} ${(indexed.has(num) ? "yes" : "no").padEnd(9)} ${desc}`);
        return { content: [{ type: "text", text: lines.join("\n") }] };
      } catch (e) {
        return { content: [{ type: "text", text: `ERROR: ${e.message}` }] };
      }
    }
  );

  server.tool(
    "kb_stats",
    "Knowledge base statistics: chunks, specs, model info.",
    {},
    async () => {
      try {
        const total     = db.prepare("SELECT COUNT(*) AS n FROM chunks").get().n;
        const specCount = db.prepare("SELECT COUNT(DISTINCT spec_id) AS n FROM chunks").get().n;
        const seriesSet = new Set(db.prepare("SELECT DISTINCT series FROM chunks").all().map(r => r.series));
        const model     = db.prepare("SELECT value FROM meta WHERE key='embed_model'").get()?.value ?? "all-MiniLM-L6-v2";
        return { content: [{ type: "text", text: [
          "3GPP Knowledge Base Statistics", "=".repeat(40),
          `Total chunks  : ${total.toLocaleString()}`,
          `Unique specs  : ${specCount}`,
          `Series        : ${seriesSet.size} (${[...seriesSet].sort().join(", ")})`,
          `Embed model   : ${model}`,
          `Transport     : HTTP/SSE`,
        ].join("\n") }] };
      } catch (e) {
        return { content: [{ type: "text", text: `ERROR: ${e.message}` }] };
      }
    }
  );

  return server;
}

// ── HTTP body reader ─────────────────────────────────────────────────────────
function readBody(req) {
  return new Promise((resolve, reject) => {
    let raw = "";
    req.on("data", chunk => (raw += chunk));
    req.on("end",  () => {
      try { resolve(JSON.parse(raw)); }
      catch { resolve(raw); }
    });
    req.on("error", reject);
  });
}

// ── Session stores ───────────────────────────────────────────────────────────
const sseSessions       = new Map(); // sessionId → SSEServerTransport
const streamableSessions = new Map(); // sessionId → StreamableHTTPServerTransport

// ── Startup ──────────────────────────────────────────────────────────────────
const dbPath = await resolveDb();
if (!dbPath) {
  die([
    "No database configured.",
    "",
    "  Local file:      KB_DB_PATH=/path/to/3gpp.db node index.js",
    "  SeaweedFS filer: SEAWEEDFS_URL=http://filer:8888/path/3gpp.db node index.js",
    "  S3/SeaweedFS:    S3_ENDPOINT=... S3_BUCKET=... S3_KEY=... S3_USER=... S3_PASS=... node index.js",
  ].join("\n"));
}

await initDb(dbPath);
await initEmbedder();

// ── HTTP server ──────────────────────────────────────────────────────────────
const httpServer = createServer(async (req, res) => {
  const url    = new URL(req.url, `http://localhost`);
  const path   = url.pathname;
  const method = req.method;

  // CORS
  res.setHeader("Access-Control-Allow-Origin",  "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, DELETE");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, mcp-session-id");
  if (method === "OPTIONS") { res.writeHead(204); res.end(); return; }

  // ── GET /health ────────────────────────────────────────────────────────────
  if (path === "/health" && method === "GET") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({
      status:       "ok",
      name:         "3gpp-telecom-kb",
      version:      "1.0.0",
      transport:    "http+sse",
      sse_sessions: sseSessions.size,
      mcp_sessions: streamableSessions.size,
      vectors:      allEmbeddings ? allEmbeddings.length / DIM : 0,
    }));
    return;
  }

  // ── GET /sse ───────────────────────────────────────────────────────────────
  // Establishes an SSE stream. Each call creates a new session.
  if (path === "/sse" && method === "GET") {
    const transport = new SSEServerTransport("/message", res);
    const server    = createMcpServer();

    sseSessions.set(transport.sessionId, transport);
    log(`SSE session opened: ${transport.sessionId} (total: ${sseSessions.size})`);

    transport.onclose = () => {
      sseSessions.delete(transport.sessionId);
      log(`SSE session closed: ${transport.sessionId} (total: ${sseSessions.size})`);
    };
    transport.onerror = (e) => {
      log(`SSE session error [${transport.sessionId}]: ${e.message}`);
    };

    await server.connect(transport);
    return;
  }

  // ── POST /message ──────────────────────────────────────────────────────────
  // Client posts MCP messages here after establishing SSE stream.
  if (path === "/message" && method === "POST") {
    const sessionId = url.searchParams.get("sessionId");
    const transport = sseSessions.get(sessionId);

    if (!transport) {
      res.writeHead(404, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "Session not found", sessionId }));
      return;
    }

    const body = await readBody(req);
    await transport.handlePostMessage(req, res, body);
    return;
  }

  // ── POST /mcp ──────────────────────────────────────────────────────────────
  // Streamable HTTP transport (modern MCP clients / web integrations).
  if (path === "/mcp" && method === "POST") {
    const sessionId = req.headers["mcp-session-id"];
    let transport;

    if (sessionId && streamableSessions.has(sessionId)) {
      transport = streamableSessions.get(sessionId);
    } else if (!sessionId) {
      transport = new StreamableHTTPServerTransport({
        sessionIdGenerator: () => randomUUID(),
        onsessioninitialized: (id) => {
          streamableSessions.set(id, transport);
          log(`Streamable session opened: ${id}`);
        },
      });
      transport.onclose = () => {
        if (transport.sessionId) {
          streamableSessions.delete(transport.sessionId);
          log(`Streamable session closed: ${transport.sessionId}`);
        }
      };
      const server = createMcpServer();
      await server.connect(transport);
    } else {
      res.writeHead(400, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "Unknown session ID" }));
      return;
    }

    await transport.handleRequest(req, res);
    return;
  }

  // ── GET / ──────────────────────────────────────────────────────────────────
  if (path === "/" && method === "GET") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({
      name:      "3gpp-telecom-kb MCP Server",
      version:   "1.0.0",
      endpoints: {
        sse:     "GET  /sse       — SSE transport (Claude Desktop, Cursor)",
        message: "POST /message   — SSE message handler (?sessionId=xxx)",
        mcp:     "POST /mcp       — Streamable HTTP (modern clients)",
        health:  "GET  /health    — health + stats",
      },
    }));
    return;
  }

  res.writeHead(404); res.end("Not found");
});

httpServer.listen(PORT, "0.0.0.0", () => {
  log(`HTTP/SSE MCP server listening on port ${PORT}`);
  log(`SSE endpoint        → http://0.0.0.0:${PORT}/sse`);
  log(`Streamable endpoint → http://0.0.0.0:${PORT}/mcp`);
  log(`Health check        → http://0.0.0.0:${PORT}/health`);
});

// Graceful shutdown
process.on("SIGTERM", () => {
  log("SIGTERM received, shutting down…");
  httpServer.close(() => process.exit(0));
});
process.on("SIGINT", () => {
  log("SIGINT received, shutting down…");
  httpServer.close(() => process.exit(0));
});

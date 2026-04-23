#!/usr/bin/env node
/**
 * 3GPP Telecom KB — Pure HTTP/SSE MCP Server v2.0.0
 * ==================================================
 * Production-ready. Always HTTP. No stdio.
 *
 * Endpoints:
 *   GET  /sse            → SSE stream (Claude Desktop, Cursor)
 *   POST /message        → SSE message handler (?sessionId=xxx)
 *   POST /mcp            → Streamable HTTP (modern clients)
 *   GET  /health         → liveness probe (always responds)
 *   GET  /ready          → readiness probe (503 until DB+model loaded)
 *
 * Config (env vars):
 *   PORT=3000
 *   KB_DB_PATH=/path/to/3gpp.db       local file (skips download)
 *   KB_DB_URL=https://...             override download URL
 *   S3_ENDPOINT, S3_BUCKET, S3_KEY, S3_USER, S3_PASS, S3_REGION
 *   SESSION_TTL_MS=1800000            stale session cleanup (default 30 min)
 *   DOWNLOAD_RETRIES=3                DB download retries (default 3)
 */

import { McpServer }                     from "@modelcontextprotocol/sdk/server/mcp.js";
import { SSEServerTransport }            from "@modelcontextprotocol/sdk/server/sse.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { z }                             from "zod";
import { existsSync, mkdirSync, createWriteStream, readFileSync, unlinkSync } from "fs";
import { homedir }                       from "os";
import { join, dirname }                 from "path";
import { createServer }                  from "http";
import { randomUUID }                    from "crypto";
import { createRequire }                 from "module";

const _require = createRequire(import.meta.url);

// ── Config ───────────────────────────────────────────────────────────────────
const PORT            = Number(process.env.PORT)             || 3000;
const SESSION_TTL_MS  = Number(process.env.SESSION_TTL_MS)  || 30 * 60 * 1000;
const DOWNLOAD_RETRIES = Number(process.env.DOWNLOAD_RETRIES) || 3;
const S3_REGION       = process.env.S3_REGION || process.env.AWS_REGION || "us-east-1";

const GITHUB_DB_URL   = "https://github.com/umeshpatildondaicha/3gpp-mcp-server/releases/download/v1.0.0/3gpp.db";
const DB_ARG          = process.env.KB_DB_PATH ?? process.env.KB_DB_URL ?? GITHUB_DB_URL;

function hasS3Cfg() {
  return Boolean(process.env.S3_USER && process.env.S3_PASS &&
                 process.env.S3_BUCKET && process.env.S3_ENDPOINT);
}

// ── Logger ───────────────────────────────────────────────────────────────────
function log(level, msg) {
  const ts = new Date().toISOString();
  process.stderr.write(`${ts} [${level}] ${msg}\n`);
}
const info  = msg => log("INFO ", msg);
const warn  = msg => log("WARN ", msg);
const error = msg => log("ERROR", msg);
const die   = msg => { error(msg); process.exit(1); };

// ── Readiness state ──────────────────────────────────────────────────────────
let isReady        = false;   // true once DB + embedder are loaded
let startupStatus  = "initializing";

// ── Download helpers ─────────────────────────────────────────────────────────
async function streamToFile(body, dest, totalBytes) {
  const writer = createWriteStream(dest);
  let done = 0, lastPct = -1;

  const tick = n => {
    done += n;
    if (totalBytes > 0) {
      const pct = Math.floor(done / totalBytes * 100);
      if (pct !== lastPct && pct % 10 === 0) {
        info(`downloading… ${pct}% (${(done / 1e6).toFixed(0)} MB / ${(totalBytes / 1e6).toFixed(0)} MB)`);
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
      body.on("data", chunk => tick(chunk.length));
      body.on("error", reject);
      writer.on("error", reject);
      writer.on("finish", resolve);
      body.pipe(writer);
    });
  }
  return done;
}

async function downloadHttp(url, dest, attempt = 1) {
  info(`downloading DB from ${url} (attempt ${attempt}/${DOWNLOAD_RETRIES})`);
  const resp = await fetch(url, { redirect: "follow" });
  if (!resp.ok) throw new Error(`HTTP ${resp.status} ${resp.statusText}`);
  const total = Number(resp.headers.get("content-length") || 0);
  const bytes = await streamToFile(resp.body, dest, total);
  info(`download complete — ${(bytes / 1e6).toFixed(0)} MB`);
  return bytes;
}

async function downloadWithRetry(url, dest) {
  for (let attempt = 1; attempt <= DOWNLOAD_RETRIES; attempt++) {
    try {
      return await downloadHttp(url, dest, attempt);
    } catch (e) {
      try { unlinkSync(dest); } catch {}
      if (attempt === DOWNLOAD_RETRIES) throw e;
      const delay = attempt * 3000;
      warn(`download failed (${e.message}), retrying in ${delay / 1000}s…`);
      await new Promise(r => setTimeout(r, delay));
    }
  }
}

async function downloadS3(dest) {
  const { S3Client, GetObjectCommand } = await import("@aws-sdk/client-s3");
  const client = new S3Client({
    region: S3_REGION, endpoint: process.env.S3_ENDPOINT, forcePathStyle: true,
    credentials: { accessKeyId: process.env.S3_USER, secretAccessKey: process.env.S3_PASS },
  });
  const key = process.env.S3_KEY || "3gpp.db";
  info(`downloading from S3: s3://${process.env.S3_BUCKET}/${key}`);
  const out = await client.send(new GetObjectCommand({ Bucket: process.env.S3_BUCKET, Key: key }));
  return streamToFile(out.Body, dest, Number(out.ContentLength || 0));
}

// ── Resolve DB path ──────────────────────────────────────────────────────────
async function resolveDb() {
  const cacheDir  = join(homedir(), ".3gpp-kb");
  const cachePath = join(cacheDir, "3gpp.db");

  // Explicit local path
  if (DB_ARG && !DB_ARG.startsWith("http")) {
    if (!existsSync(DB_ARG)) die(`DB file not found: ${DB_ARG}`);
    info(`using local DB: ${DB_ARG}`);
    return DB_ARG;
  }

  // Use cache if available
  if (existsSync(cachePath)) {
    info(`using cached DB: ${cachePath}`);
    return cachePath;
  }

  mkdirSync(cacheDir, { recursive: true });

  // S3 takes priority over URL if configured
  if (hasS3Cfg()) {
    startupStatus = "downloading-db-s3";
    await downloadS3(cachePath).catch(e => die(`S3 download failed: ${e.message}`));
    return cachePath;
  }

  // HTTP/HTTPS URL (GitHub Releases, HuggingFace, etc.)
  startupStatus = "downloading-db";
  info(`DB not cached — downloading (~1.8 GB, this takes a few minutes on first start)`);
  await downloadWithRetry(DB_ARG, cachePath).catch(e => die(`Download failed: ${e.message}`));
  return cachePath;
}

// ── Database (sql.js WASM — zero native compilation) ─────────────────────────
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
  startupStatus = "loading-db";
  const { default: initSqlJs } = await import("sql.js");
  const wasmPath = join(dirname(_require.resolve("sql.js")), "sql-wasm.wasm");
  const SQL      = await initSqlJs({ locateFile: () => wasmPath });

  info("opening DB into memory…");
  db = wrapDb(new SQL.Database(readFileSync(dbPath)));

  info("loading embeddings…");
  const rows = db.prepare("SELECT chunk_id, vector FROM embeddings ORDER BY rowid").all();
  const n    = rows.length;
  allEmbeddings = new Float32Array(n * DIM);
  chunkIds      = new Array(n);
  rows.forEach((row, i) => {
    chunkIds[i] = row.chunk_id;
    allEmbeddings.set(new Float32Array(row.vector.buffer, row.vector.byteOffset, DIM), i * DIM);
  });

  chunkMeta = new Map();
  db.prepare("SELECT id, spec_id, release, series, series_desc, doc_type, title FROM chunks")
    .all().forEach(r => chunkMeta.set(r.id, r));

  info(`${n.toLocaleString()} vectors loaded (${(n * DIM * 4 / 1e6).toFixed(0)} MB RAM)`);
}

// ── Embedding model ───────────────────────────────────────────────────────────
let embedder;

async function initEmbedder() {
  startupStatus = "loading-model";
  info("loading embedding model…");
  const { pipeline, env } = await import("@huggingface/transformers");
  env.allowLocalModels    = false;
  embedder = await pipeline("feature-extraction", "Xenova/all-MiniLM-L6-v2");
  info("embedding model ready.");
}

async function embed(text) {
  const out = await embedder(text, { pooling: "mean", normalize: true });
  return new Float32Array(out.data);
}

// ── Search ────────────────────────────────────────────────────────────────────
function cosineTopK(qvec, k, { series, release, doc_type } = {}) {
  const results = [];
  for (let i = 0; i < chunkIds.length; i++) {
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

function doSearch(qvec, topK, filters) {
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
  if (!hits?.length) return "No results found.";
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

// ── MCP server factory (one per session) ─────────────────────────────────────
function createMcpServer() {
  const server = new McpServer({ name: "3gpp-telecom-kb", version: "2.0.0" });

  server.tool("search_3gpp",
    "Semantic search across 3GPP specifications. Filters: series ('38'=5G NR, '36'=LTE, " +
    "'23'=Arch, '29'=Core, '33'=Security), release ('Rel-17','Rel-18','Rel-19'), doc_type ('TS'/'TR').",
    {
      query:    z.string().describe("Natural-language question"),
      top_k:    z.number().int().min(1).max(50).optional().default(10),
      series:   z.string().optional(),
      release:  z.string().optional(),
      doc_type: z.enum(["TS","TR"]).optional(),
    },
    async ({ query, top_k, series, release, doc_type }) => {
      try {
        const hits = doSearch(await embed(query), top_k, { series, release, doc_type });
        return { content: [{ type: "text", text: formatHits(hits, query) }] };
      } catch (e) { return { content: [{ type: "text", text: `ERROR: ${e.message}` }] }; }
    }
  );

  server.tool("get_spec_info",
    "Retrieve text chunks of a specific 3GPP spec by ID (e.g. '38.331', '23.501').",
    { spec_id: z.string(), max_chunks: z.number().int().min(1).max(20).optional().default(5) },
    async ({ spec_id, max_chunks }) => {
      try {
        const rows = db.prepare(
          "SELECT * FROM chunks WHERE spec_id=? ORDER BY chunk_index LIMIT ?"
        ).all(spec_id, max_chunks);
        if (!rows.length)
          return { content: [{ type: "text", text: `Spec '${spec_id}' not found.` }] };
        const m = rows[0];
        const lines = [
          `=== 3GPP ${m.doc_type} ${m.spec_id} ===`,
          `Title   : ${m.title}`, `Release : ${m.release}`,
          `Series  : ${m.series} — ${m.series_desc}`,
          `Chunks  : showing ${rows.length} of ${m.total_chunks} total\n`,
        ];
        rows.forEach(r => { lines.push(`--- Chunk ${r.chunk_index + 1} ---`); lines.push(r.text, ""); });
        return { content: [{ type: "text", text: lines.join("\n") }] };
      } catch (e) { return { content: [{ type: "text", text: `ERROR: ${e.message}` }] }; }
    }
  );

  server.tool("list_specs",
    "List all 3GPP specs, optionally filtered by series or release.",
    { series: z.string().optional(), release: z.string().optional() },
    async ({ series, release }) => {
      try {
        let sql = `SELECT spec_id, series, series_desc, release, doc_type,
                          MAX(total_chunks) AS total_chunks FROM chunks`;
        const where = [], params = [];
        if (series)  { where.push("series=?");  params.push(series); }
        if (release) { where.push("release=?"); params.push(release); }
        if (where.length) sql += " WHERE " + where.join(" AND ");
        sql += " GROUP BY spec_id ORDER BY spec_id";
        const specs = db.prepare(sql).all(...params);
        if (!specs?.length) return { content: [{ type: "text", text: "No specs found." }] };
        return { content: [{ type: "text", text: [
          `Indexed 3GPP specs (${specs.length} total)\n`,
          `${"Spec ID".padEnd(14)} ${"Type".padEnd(5)} ${"Release".padEnd(10)} ${"Chunks".padEnd(8)} Series`,
          "-".repeat(70),
          ...specs.map(s =>
            `${s.spec_id.padEnd(14)} ${s.doc_type.padEnd(5)} ${s.release.padEnd(10)} ${String(s.total_chunks).padEnd(8)} ${s.series_desc}`
          ),
        ].join("\n") }] };
      } catch (e) { return { content: [{ type: "text", text: `ERROR: ${e.message}` }] }; }
    }
  );

  server.tool("list_series", "3GPP series catalog with index status.", {},
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
      } catch (e) { return { content: [{ type: "text", text: `ERROR: ${e.message}` }] }; }
    }
  );

  server.tool("kb_stats", "Knowledge base statistics.", {},
    async () => {
      try {
        const total     = db.prepare("SELECT COUNT(*) AS n FROM chunks").get().n;
        const specCount = db.prepare("SELECT COUNT(DISTINCT spec_id) AS n FROM chunks").get().n;
        const series    = new Set(db.prepare("SELECT DISTINCT series FROM chunks").all().map(r => r.series));
        const model     = db.prepare("SELECT value FROM meta WHERE key='embed_model'").get()?.value ?? "all-MiniLM-L6-v2";
        return { content: [{ type: "text", text: [
          "3GPP Knowledge Base Statistics", "=".repeat(40),
          `Total chunks  : ${total.toLocaleString()}`,
          `Unique specs  : ${specCount}`,
          `Series        : ${series.size} (${[...series].sort().join(", ")})`,
          `Embed model   : ${model}`,
          `Transport     : HTTP/SSE`,
          `Server version: 2.0.0`,
        ].join("\n") }] };
      } catch (e) { return { content: [{ type: "text", text: `ERROR: ${e.message}` }] }; }
    }
  );

  return server;
}

// ── Session stores with TTL cleanup ──────────────────────────────────────────
const sseSessions        = new Map(); // id → { transport, createdAt }
const streamableSessions = new Map(); // id → { transport, createdAt }

function cleanupSessions() {
  const cutoff = Date.now() - SESSION_TTL_MS;
  for (const [id, s] of sseSessions) {
    if (s.createdAt < cutoff) {
      try { s.transport.close(); } catch {}
      sseSessions.delete(id);
      warn(`SSE session expired: ${id}`);
    }
  }
  for (const [id, s] of streamableSessions) {
    if (s.createdAt < cutoff) {
      try { s.transport.close(); } catch {}
      streamableSessions.delete(id);
      warn(`Streamable session expired: ${id}`);
    }
  }
}
setInterval(cleanupSessions, 5 * 60 * 1000).unref(); // run every 5 min

// ── HTTP body reader ──────────────────────────────────────────────────────────
function readBody(req) {
  return new Promise((resolve, reject) => {
    let raw = "";
    req.on("data", chunk => (raw += chunk));
    req.on("end",  () => { try { resolve(JSON.parse(raw)); } catch { resolve(raw); } });
    req.on("error", reject);
  });
}

// ── JSON response helper ──────────────────────────────────────────────────────
function json(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

// ── Startup ───────────────────────────────────────────────────────────────────
const dbPath = await resolveDb();
await initDb(dbPath);
await initEmbedder();
isReady = true;
startupStatus = "ready";

// ── HTTP server ───────────────────────────────────────────────────────────────
const httpServer = createServer(async (req, res) => {
  const url    = new URL(req.url, "http://localhost");
  const path   = url.pathname;
  const method = req.method;

  // CORS
  res.setHeader("Access-Control-Allow-Origin",  "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, DELETE");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, mcp-session-id");
  if (method === "OPTIONS") { res.writeHead(204); res.end(); return; }

  // ── GET /health — liveness (always responds) ──────────────────────────────
  if (path === "/health" && method === "GET") {
    json(res, 200, {
      status:        "ok",
      ready:         isReady,
      startup_phase: startupStatus,
      name:          "3gpp-telecom-kb",
      version:       "2.0.0",
      uptime_sec:    Math.floor(process.uptime()),
      sse_sessions:  sseSessions.size,
      mcp_sessions:  streamableSessions.size,
      vectors:       allEmbeddings ? allEmbeddings.length / DIM : 0,
    });
    return;
  }

  // ── GET /ready — readiness (503 until fully initialized) ─────────────────
  if (path === "/ready" && method === "GET") {
    if (!isReady) {
      json(res, 503, { ready: false, phase: startupStatus });
    } else {
      json(res, 200, { ready: true });
    }
    return;
  }

  // ── Reject requests until ready ───────────────────────────────────────────
  if (!isReady) {
    json(res, 503, { error: "Server initializing", phase: startupStatus });
    return;
  }

  // ── GET / — info ──────────────────────────────────────────────────────────
  if (path === "/" && method === "GET") {
    json(res, 200, {
      name: "3gpp-telecom-kb MCP Server", version: "2.0.0",
      endpoints: {
        sse:     "GET  /sse      — SSE stream (Claude Desktop, Cursor)",
        message: "POST /message  — SSE message handler (?sessionId=xxx)",
        mcp:     "POST /mcp      — Streamable HTTP (modern clients)",
        health:  "GET  /health   — liveness probe",
        ready:   "GET  /ready    — readiness probe",
      },
    });
    return;
  }

  // ── GET /sse ──────────────────────────────────────────────────────────────
  if (path === "/sse" && method === "GET") {
    const transport = new SSEServerTransport("/message", res);
    const server    = createMcpServer();
    const id        = transport.sessionId;

    sseSessions.set(id, { transport, createdAt: Date.now() });
    info(`SSE session opened: ${id} (active: ${sseSessions.size})`);

    transport.onclose = () => {
      sseSessions.delete(id);
      info(`SSE session closed: ${id} (active: ${sseSessions.size})`);
    };
    transport.onerror = e => warn(`SSE session error [${id}]: ${e.message}`);

    await server.connect(transport);
    return;
  }

  // ── POST /message ─────────────────────────────────────────────────────────
  if (path === "/message" && method === "POST") {
    const sessionId = url.searchParams.get("sessionId");
    const session   = sseSessions.get(sessionId);
    if (!session) {
      json(res, 404, { error: "Session not found", sessionId });
      return;
    }
    const body = await readBody(req);
    await session.transport.handlePostMessage(req, res, body);
    return;
  }

  // ── POST /mcp ─────────────────────────────────────────────────────────────
  if (path === "/mcp" && method === "POST") {
    const sessionId = req.headers["mcp-session-id"];
    let transport;

    if (sessionId && streamableSessions.has(sessionId)) {
      transport = streamableSessions.get(sessionId).transport;
    } else if (!sessionId) {
      transport = new StreamableHTTPServerTransport({
        sessionIdGenerator: () => randomUUID(),
        onsessioninitialized: id => {
          streamableSessions.set(id, { transport, createdAt: Date.now() });
          info(`Streamable session opened: ${id} (active: ${streamableSessions.size})`);
        },
      });
      transport.onclose = () => {
        if (transport.sessionId) {
          streamableSessions.delete(transport.sessionId);
          info(`Streamable session closed: ${transport.sessionId}`);
        }
      };
      await createMcpServer().connect(transport);
    } else {
      json(res, 400, { error: "Unknown session ID" });
      return;
    }

    await transport.handleRequest(req, res);
    return;
  }

  res.writeHead(404); res.end("Not found");
});

httpServer.listen(PORT, "0.0.0.0", () => {
  info(`HTTP/SSE MCP server ready on port ${PORT}`);
  info(`SSE endpoint   → http://0.0.0.0:${PORT}/sse`);
  info(`MCP endpoint   → http://0.0.0.0:${PORT}/mcp`);
  info(`Health         → http://0.0.0.0:${PORT}/health`);
  info(`Readiness      → http://0.0.0.0:${PORT}/ready`);
});

// ── Graceful shutdown ─────────────────────────────────────────────────────────
function shutdown(sig) {
  info(`${sig} received — shutting down gracefully…`);
  httpServer.close(() => {
    info("HTTP server closed.");
    process.exit(0);
  });
  setTimeout(() => { error("Forced shutdown after timeout."); process.exit(1); }, 10000).unref();
}
process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT",  () => shutdown("SIGINT"));
process.on("uncaughtException",  e => { error(`Uncaught exception: ${e.stack}`); });
process.on("unhandledRejection", e => { error(`Unhandled rejection: ${e}`); });

# 3GPP Telecom Knowledge-Base MCP Server

Spring Boot (Java 21) MCP server exposing semantic search over a curated telecom
corpus — 3GPP TS/TR, ITU-T, IETF RFC, ETSI NFV, vendor CLI — via the
`search3gpp` / `search3gppBatch` tools. Retrieval is hybrid: BGE-M3 dense
vectors + SQLite FTS5 BM25, fused with RRF, reranked by a cross-encoder, with
sentence-level answer extraction.

## Runtime artifacts — nothing from the internet

| Artifact | Where it lives | How the server gets it |
|---|---|---|
| KB SQLite DBs (2.1 GB + 0.7 GB) | SeaweedFS `buckets/performance/3gpp-models/3gpp_kb/` | initContainer copies to a local volume (`KB_DB_PATH`/`KB_DB_PATH2`) |
| BGE-M3 embed model + tokenizer | SeaweedFS `…/bge-m3/` | app streams via `EMBED_MODEL_URI` / `EMBED_TOKENIZER_URI` |
| mxbai reranker + tokenizer | SeaweedFS `…/mxbai/` | app streams via `RERANKER_MODEL_URI` / `RERANKER_TOKENIZER_URI` |
| PyTorch natives | inside the jar | `pytorch-native-cpu:linux-x86_64` dep in pom.xml |

There is deliberately no `3gpp_kb/` folder in this repo — the object store is
the canonical home of the KB. For local runs, pull the DBs from the bucket and
point `KB_DB_PATH`/`KB_DB_PATH2` at them.

## Layout

    src/                    application code (com.vwaves.mcp)
      service/              retrieval pipeline: KbDataService (dense+FTS5),
                            RerankService (cross-encoder + sentence selection),
                            ThreeGppToolService (MCP tools), EmbeddingService
      config/               typed @ConfigurationProperties for retrieval tuning
    k8s/
      railtel-production.yaml   THE production manifest: 4 STATELESS replicas,
                                initContainer DB fetch, S3 model URIs, probes
      deployment.yaml           older single-replica dev deployment
    bench/                  benchmark suite — run against a live server
      benchmark_oam.py      50-question OAM accuracy (TOP1/TOP5) — run after
                            touching any retrieval knob in application.properties
      bench_verbosity.py + judge_verbosity.py   answer-compression quality
      bench_latency.py      latency profile
      questions_broad.py    50-question broad retrieval suite
      baselines/            committed result JSONs the tuning comments refer to
    docs/                   DEPLOYMENT.md (DB contents/provenance),
                            README.production.md, competitive analyses
    pg/                     optional future migration path to pgvector
    ui/                     minimal demo UI
    Dockerfile              production image (visionwaves pattern)
    run.sh                  container entrypoint (invoked by Dockerfile CMD)
    start.sh                local dev launcher (mvn / jar modes)
    PRODUCTION_ROLLOUT.md   START HERE for deploys: steps, verification,
                            measured baselines, client-timeout prerequisite

## Quick local run

    mvn -DskipTests package
    KB_DB_PATH=/path/3gpp_certified.db KB_DB_PATH2=/path/telecom_extras.db \
    EMBED_MODEL_URI=... EMBED_TOKENIZER_URI=... \
    RERANKER_MODEL_URI=... RERANKER_TOKENIZER_URI=... \
    PORT=3001 java -jar target/3gpp-mcp-server-2.0.0.jar

Then `POST /mcp` (stateless streamable HTTP) with a `tools/call` for
`search3gpp`. See PRODUCTION_ROLLOUT.md for a curl example.

## Performance facts (measured 2026-08-10, keep in mind when scaling)

- Single search ≈ 2–3.6 s (CPU-bound: embed + 196k-vector scan + rerank).
- One JVM saturates at ~4 concurrent searches; more concurrency adds latency,
  not throughput. Scale with replicas — STATELESS protocol makes every pod
  interchangeable.
- Batch calls pin to one pod; prefer several 10–12-query batches in parallel
  over one giant call. MCP clients must allow ≥120 s per tool call.

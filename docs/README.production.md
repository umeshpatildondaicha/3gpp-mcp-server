# Production Deployment Guide — 3GPP MCP Server

Audience: ops / SRE deploying the JAR or container to a real environment.

The application **never bundles the SQLite knowledge bases inside the JAR or
container image** (combined ~3.4 GB). They must be mounted at runtime from a
persistent volume, host bind-mount, or pre-staged path. The previous
"Classpath DB resource not found" failure happened because env vars were not
set and the JVM fell through to its in-classpath fallback, which intentionally
does not exist.

---

## 1. Required environment variables

| Variable | Default | Required? | What it does |
|---|---|---|---|
| `KB_DB_PATH`         | `classpath:3gpp_certified.db` (does not exist) | **YES** | Absolute path to the primary 3GPP DB (~3.3 GB). |
| `KB_DB_PATH2`        | `classpath:telecom_extras.db` (does not exist) | **YES** | Absolute path to the extras DB (~37 MB). |
| `PORT` / `MELODY_PORT` | `3000` | no | TCP port. |
| `RERANKER_ENABLED`     | `true`  | no | Set `false` to skip the 90 MB cross-encoder model. |
| `RERANKER_MODEL_URI`   | mxbai-rerank-xsmall-v1 (45 MB INT8) | no | Override to swap reranker. |
| `RERANKER_TOKENIZER_URI` | matching tokenizer | no | |
| `HF_TOKEN`             | unset   | only when using gated reranker (`bge-reranker-v2-m3`) |
| `RERANK_CANDIDATES`    | `24`    | no | Larger pool = slower but more accurate. |
| `GLOSSARY_PATH`        | `classpath:3gpp-vocab.tsv` | no | TSV of `ABBREV<TAB>expansion`. |
| All `RETRIEVAL_*` vars | (see `application.properties`) | no | RRF / discount / cap tuning. |

`KB_DB_PATH` and `KB_DB_PATH2` MUST point at SQLite files that contain a
`chunks` table; `DbResolver` validates this at startup and fails with a clear
remediation message.

---

## 2. Run modes

### A. Local dev (working directory = repo root)

```bash
./start.sh                # mvn spring-boot:run, hot reload of resources
./start.sh build          # mvn package, then run target/3gpp-mcp-server-2.0.0.jar
```

`start.sh` exports the env vars, sanity-checks the DB paths, and refuses to
start if port 3000 is already bound.

### B. Standalone JAR

```bash
export KB_DB_PATH=/var/lib/3gpp-kb/3gpp_certified.db
export KB_DB_PATH2=/var/lib/3gpp-kb/telecom_extras.db
java -jar target/3gpp-mcp-server-2.0.0.jar
```

### C. Docker

The container image **does not contain the DBs**. Bind-mount or copy them in.

```bash
# build
mvn -q -DskipTests package
# run with bind-mount
docker run --rm -p 3000:3000 \
  -v /host/path/3gpp_kb:/var/lib/3gpp-kb:ro \
  -e KB_DB_PATH=/var/lib/3gpp-kb/3gpp_certified.db \
  -e KB_DB_PATH2=/var/lib/3gpp-kb/telecom_extras.db \
  ghcr.io/.../3gpp-mcp-server:latest
```

Image exposes **3000** (matches `PORT` default in `application.properties`).
Healthcheck hits `/actuator/health/liveness`.

### D. Kubernetes

`k8s/deployment.yaml` is wired for the current 2-DB layout:

- container port 3000
- env vars `KB_DB_PATH` and `KB_DB_PATH2` pointing under `/var/lib/3gpp-kb/...`
- liveness probe at `/actuator/health/liveness`
- readiness probe at `/actuator/health/readiness`
- startup probe at `/actuator/health` (gives the cold start ~5 min before the readiness probe takes over)
- resource limits: requests 3 Gi RAM / 0.5 CPU, limits 8 Gi RAM / 2 CPU
- 10 Gi PersistentVolumeClaim mounted at `/var/lib/3gpp-kb`

Apply:

```bash
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
```

#### How to seed the PVC with the DBs

The PVC is empty after creation. You need to copy the two DBs into it before
the pod becomes ready. Three common patterns:

1. **`kubectl cp` from a workstation that has the files** (one-shot, fine for staging):
   ```bash
   # exec a temporary helper pod with the PVC mounted, then copy
   kubectl run kb-seed --image=busybox --restart=Never --command -- sleep 3600 \
       --overrides='{"spec":{"containers":[{"name":"kb-seed","image":"busybox","volumeMounts":[{"name":"kb","mountPath":"/var/lib/3gpp-kb"}]}],"volumes":[{"name":"kb","persistentVolumeClaim":{"claimName":"3gpp-db-pvc"}}]}}'
   kubectl cp ./3gpp_kb/3gpp_certified.db  kb-seed:/var/lib/3gpp-kb/3gpp_certified.db
   kubectl cp ./3gpp_kb/telecom_extras.db  kb-seed:/var/lib/3gpp-kb/telecom_extras.db
   kubectl delete pod kb-seed
   ```
2. **InitContainer that pulls from object storage** (production):
   ```yaml
   initContainers:
     - name: kb-seed
       image: amazon/aws-cli
       command:
         - sh
         - -c
         - >
           [ -f /var/lib/3gpp-kb/3gpp_certified.db ] ||
           aws s3 cp s3://my-bucket/3gpp_kb/ /var/lib/3gpp-kb/ --recursive
       volumeMounts:
         - name: kb-data
           mountPath: /var/lib/3gpp-kb
   ```
3. **Bake into the image** (only feasible with smaller indexes — not this one).

---

## 3. Pre-flight checklist

Before flipping a production switch:

- [ ] Both DB files are reachable at the configured `KB_DB_PATH` /
      `KB_DB_PATH2` and contain a `chunks` table:
      `sqlite3 $KB_DB_PATH "SELECT COUNT(*) FROM chunks"` → > 0.
- [ ] `meta.embed_model = 'BAAI/bge-m3'` and `meta.embed_dim = '1024'` in both
      DBs (the runtime fails fast on mismatch).
      `sqlite3 $KB_DB_PATH "SELECT key, value FROM meta WHERE key IN ('embed_model','embed_dim')"`.
- [ ] `target/*.jar` builds cleanly with `mvn -q -DskipTests package`.
- [ ] `/actuator/health/liveness` returns 200 within 30 s after Tomcat
      logs `Started Application`.
- [ ] `/actuator/health/readiness` returns 200 once embeddings + reranker
      are warmed (typically ~30 s after liveness).
- [ ] First MCP `tools/call` for `search3gpp` succeeds end-to-end.

---

## 4. Smoke test (Python, no MCP client needed)

```bash
MCP_URL=http://your-host:3000/mcp python3 benchmark_oam.py
```

Expected output (with the current corpus + tunables):

```
TOP1: 37/50 = 74%
TOP5: 49/50 = 98%
```

If TOP1 falls below 35 you have a regression — check the server log for
"glossary loaded" / "lexicons loaded" / dim-mismatch warnings and the
`extras_db_weight` field in `query_logger` output.

---

## 5. Common failure modes

| Symptom | Cause | Fix |
|---|---|---|
| `Startup failed: ... knowledge-base DB not found.` | `KB_DB_PATH` / `KB_DB_PATH2` unset or pointing nowhere | Set both env vars to absolute paths of valid SQLite files. |
| `Embedding model mismatch — index built with 'X' but runtime is 'Y'` | DB built with a different embedder | Re-ingest with `BAAI/bge-m3` OR override `EMBED_MODEL_NAME` and `EMBED_MODEL_URI` to match. |
| `Embedding dimension mismatch` | DB has `meta.embed_dim` ≠ runtime dim | Re-ingest, or set `EMBED_DIM` to the indexed dim. |
| `DB[X] vector byte-length N ≠ expected M` | Mixed-dim DBs | One DB has a different embedding model — drop dense, BM25 still works. |
| `glossary file not found` | `app.glossary-path` resource missing | Either ship `3gpp-vocab.tsv` on the classpath or set `GLOSSARY_PATH=file:/path/to/vocab.tsv`. |
| Pod restarts every 30s in k8s | Probes hit wrong path | Probe paths must be `/actuator/health/{liveness,readiness}`, not `/health`. |
| Reranker init slow on every restart | Cache directory is ephemeral | Mount `~/.cache/3gpp-mcp/` on the persistent volume (env `HOME=/var/lib/3gpp-kb/cache`). |

---

## 6. Capacity sizing

- **RAM**: vectors are 188,652 × 1024 × 4 bytes = ~770 MB resident. JVM heap
  roughly equal to that for working set, plus reranker ONNX (~50 MB) and
  embedding model (~600 MB ONNX). Plan for **3 GB heap, 6 GB pod**.
- **CPU**: cold start uses 2 cores for ~30 s loading vectors and rebuilding FTS5
  if missing. Steady-state per query: 1 dense scan + 1 BM25 query + 24
  reranker forward passes (~150–250 ms wall clock on Apple M2 / cloud x86).
- **Disk**: 4 GB for DBs + 1 GB for the reranker ONNX cache.

---

## 7. Where the model parity is enforced

Three checks at startup, all fail-fast:

1. `EmbeddingService.init()` warms the model and asserts the produced vector
   length equals `app.embed-dim`.
2. `BootstrapService.initialize()` reads `meta.embed_model` from each DB and
   compares against `app.embed-model-name` (case-insensitive).
3. `BootstrapService.initialize()` reads `meta.embed_dim` from each DB and
   compares against the runtime dim.

If any check fails the JVM exits non-zero so k8s will surface the failure via
`CrashLoopBackoff`.

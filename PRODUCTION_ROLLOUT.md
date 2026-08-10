# Production rollout — S3-sourced, fully offline (2026-08-10)

Everything the service needs at runtime comes from **in-cluster SeaweedFS** or
the jar itself. Production pods never touch the internet. This replaces the
setup that downloaded ~755 MB from huggingface.co/publish.djl.ai on every
container start and crash-looped the prod pod 227 times when the downloads
outran the liveness probe.

## What lives where

| Artifact | Source at runtime | How |
|---|---|---|
| BGE-M3 embed model + tokenizer (570+17 MB) | SeaweedFS | app streams via `EMBED_MODEL_URI` / `EMBED_TOKENIZER_URI` |
| mxbai reranker + tokenizer (87+9 MB) | SeaweedFS | app streams via `RERANKER_MODEL_URI` / `RERANKER_TOKENIZER_URI` |
| PyTorch/libtorch natives (~105 MB) | inside the jar | `pytorch-jni` + `pytorch-native-cpu:linux-x86_64` deps in pom.xml |
| SQLite KBs (2.1 GB + 0.7 GB) | SeaweedFS → pod-local emptyDir | initContainer curl (SQLite needs a local random-access file) |

Bucket layout (staging filer, `swf` namespace):

    http://seaweedfs-filer.swf.svc.cluster.local:8888/buckets/performance/3gpp-models/
        bge-m3/model_quantized.onnx     bge-m3/tokenizer.json
        mxbai/model_quantized.onnx      mxbai/tokenizer.json
        3gpp_kb/3gpp_certified.db       3gpp_kb/telecom_extras.db

## Rollout steps

1. `mvn -DskipTests package` → build/push image (`docker/` bundle as usual).
   The jar MUST contain `pytorch-native-cpu-2.5.1-linux-x86_64.jar`
   (`unzip -l target/*.jar | grep pytorch-native`).
2. Upload the `3gpp-models/` tree above to the **target cluster's** SeaweedFS
   `performance` bucket, and confirm its filer DNS (staging is
   `seaweedfs-filer.swf.svc.cluster.local:8888` — verify prod matches).
3. Apply `k8s/railtel-production.yaml` after setting the image tag.
   It carries the measured-and-decided config: 4 STATELESS replicas + HPA(8),
   initContainer DB fetch, startupProbe 10s×60, cpu 2/3, RERANK_CANDIDATES
   left at default 24 (12 was measured: −1.5 s/call but OAM TOP5 88%→82%).
4. **agenticframework-service** (separate repo/team): `McpServerLoader` must add
   `.requestTimeout(Duration.ofSeconds(120))` to the `McpClient.sync(...)`
   builder. The MCP SDK defaults to 20 s and IGNORES
   `spring.ai.mcp.client.request-timeout` for hand-built clients; without this,
   any `search3gppBatch` ≥ ~6 queries and any pile-up latency >20 s fails.

## Expected startup (in-cluster)

- New pod: ~1–2 min (initContainer copy + model stream + 197k-vector load + warmup)
- Container restart in the same pod: ~30–60 s (emptyDir keeps the DBs)
- startupProbe budget is 10 min, so slow storage can never crash-loop it.

## Verification after deploy

    kubectl get pods -l app.kubernetes.io/name=mcp-3gpp-service   # all Ready, RESTARTS 0
    # in a pod log, confirm model URLs point at seaweedfs-filer, not huggingface
    # then a live search via the stateless endpoint:
    curl -s -X POST http://mcp-3gpp-service/mcp-3gpp-service/mcp \
      -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
      -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search3gpp","arguments":{"query":"PRB usage measurement in 5G NR","topK":3}}}'

Measured behaviour to expect (2026-08-10 baselines): single search ~2–3.6 s,
batch(10) ~26 s, one pod saturates at 4 concurrent searches — throughput scales
with replicas, not with concurrency per pod.

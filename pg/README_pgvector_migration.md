# 3GPP KB → Postgres 16 + pgvector (local setup + migration)

This replaces the two on-disk SQLite files (`3gpp_certified.db`, `telecom_extras.db`)
with a single Postgres database. The pod/JVM no longer loads ~192k vectors into
heap — it issues an indexed query and gets back only the top‑k rows.

Files in this folder:
- `docker-compose.yml` — Postgres 16 + pgvector, auto-applies the schema on first boot
- `schema.sql` — table + vector/HNSW + FTS/GIN + filter indexes (mirrors the SQLite tables)
- `migrate_sqlite_to_pg.py` — streams both SQLite DBs into Postgres, rebuilds indexes after load

---

## 1. Start Postgres (Docker Desktop — recommended)

```bash
cd pg/                       # this folder
docker compose up -d
docker compose logs -f pg    # wait for "database system is ready to accept connections"
```

DB is now at `postgresql://kb:kb@localhost:5432/threegpp_kb` with the schema applied.

### Homebrew alternative (no Docker)
```bash
brew install postgresql@16 pgvector
brew services start postgresql@16
createuser -s kb 2>/dev/null; psql postgres -c "ALTER ROLE kb PASSWORD 'kb';"
createdb -O kb threegpp_kb
psql "postgresql://kb:kb@localhost:5432/threegpp_kb" -f schema.sql
```

## 2. Migrate the data

```bash
pip install psycopg2-binary
python3 migrate_sqlite_to_pg.py \
  --certified ../3gpp_kb/3gpp_certified.db \
  --extras    ../3gpp_kb/telecom_extras.db \
  --dsn "postgresql://kb:kb@localhost:5432/threegpp_kb"
```

Full load is ~192k rows; the HNSW index build is the slow step (a few minutes).
`db_origin` = 0 (certified) / 1 (extras) replaces the old `0:`/`1:` chunk-id prefix.

## 3. Verify

```sql
-- counts
SELECT count(*) chunks, count(DISTINCT spec_id) specs FROM chunks;
-- dense KNN (probe = any stored vector)
SET hnsw.ef_search = 100;
SELECT spec_id, embedding <=> (SELECT embedding FROM chunks WHERE id='<some-id>') AS d
FROM chunks ORDER BY d LIMIT 5;
-- lexical
SELECT spec_id, ts_rank_cd(fts,q) FROM chunks, websearch_to_tsquery('english','network slice') q
WHERE fts @@ q ORDER BY 2 DESC LIMIT 5;
```

`EXPLAIN` on the dense query should show `Index Scan using chunks_embedding_hnsw`.

---

## 4. Point the Java server at Postgres

`pom.xml` — swap `org.xerial:sqlite-jdbc` for:
```xml
<dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><version>42.7.4</version></dependency>
<dependency><groupId>com.pgvector</groupId><artifactId>pgvector</artifactId><version>0.1.6</version></dependency>
```

`application.properties`:
```
spring.datasource.url=jdbc:postgresql://localhost:5432/threegpp_kb
spring.datasource.username=kb
spring.datasource.password=kb
```

### What changes in `KbDataService` (retrieval layer only)
Everything else — BGE-M3 embedding, RRF fusion, TR/extras discounts, alias-pin
boost, the cross-encoder reranker, sentence selection — stays identical.

- `init()/loadEmbeddings()` — **delete**. No more in-RAM `float[]` of all vectors;
  no `allEmbeddings`, no ~800 MB heap pin. This is the whole point.
- `cosineTopK()` → one SQL query:
  `SELECT id, 1-(embedding <=> :q) AS score FROM chunks
   WHERE (:series IS NULL OR series=:series) AND (:release IS NULL OR release=:release)
   ORDER BY embedding <=> :q LIMIT :pool` (bind :q via pgvector's `PGvector`).
- `bm25TopK()` → `websearch_to_tsquery` / `to_tsquery` + `ts_rank_cd`, same
  AND→AND-1→OR fallback logic, just built as tsquery strings.
- `hybridSearch()` — the RRF fusion can stay in Java over the two result lists,
  **or** move into one SQL statement (see the CTE in section 3 of the test).
- `getSpecChunks/listSpecs/indexedSeries/totalChunks/...` — straight SQL rewrites.
- `buildFts5IfMissing()` — **delete**; the `fts` column + GIN index are maintained by Postgres.

## 5. Lexical parity note (important for your benchmarks)
SQLite FTS5 `unicode61` and Postgres FTS tokenize/score differently (e.g. `S-GW`
splitting, BM25 vs `ts_rank_cd`). Expect the lexical half to shift, so **re-run
`benchmark_oam.py` / `bench_verbosity.py` and re-tune** against your saved
baselines after cutover — this fits your existing "change a knob → re-benchmark"
workflow. If you want true BM25 semantics inside Postgres, use the ParadeDB
`pg_search` extension instead of native `tsvector`; it's the closest match to
FTS5 behaviour and keeps everything in one engine.

## Notes
- "word is too long to be indexed" during load = binary/garbage chunks from PDF
  extraction (a known corpus issue). Harmless; the server's `looksBinary()` guard
  already filters them at query time.
- Production: run Postgres as managed (RDS/Cloud SQL/Neon) or via the CloudNativePG
  operator on k8s with backups to object storage (SeaweedFS/S3 fits here as the
  backup/WAL-archive target). The app stays stateless.

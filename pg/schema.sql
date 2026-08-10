-- ============================================================================
-- 3GPP KB — Postgres 16 + pgvector schema (replaces both SQLite DBs)
-- Auto-applied by docker-compose on first boot. Safe to re-run.
-- ============================================================================
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS chunks (
  id            TEXT PRIMARY KEY,             -- original chunk id (no 0:/1: prefix)
  db_origin     SMALLINT NOT NULL DEFAULT 0,  -- 0=certified, 1=extras (replaces prefix routing)
  spec_id       TEXT NOT NULL,
  release       TEXT,
  series        TEXT,
  series_desc   TEXT,
  doc_type      TEXT,                         -- TS / TR / REC / RFC / ...
  source        TEXT,                         -- '' | 3gpp | ietf | itu-t | oran | ...
  title         TEXT,
  text          TEXT,
  chunk_index   INTEGER,
  total_chunks  INTEGER,
  embedding     vector(1024),                 -- L2-normalised BGE-M3 vector
  -- BM25/FTS surface. text + series_desc mirrors the AND-query column scope
  -- in KbDataService (title deliberately excluded there).
  fts           tsvector GENERATED ALWAYS AS (
                   to_tsvector('english', coalesce(text,'') || ' ' || coalesce(series_desc,''))
                 ) STORED
);

-- Dense ANN index (vectors are L2-normalised -> cosine distance operator <=>).
CREATE INDEX IF NOT EXISTS chunks_embedding_hnsw
  ON chunks USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
-- Lexical index.
CREATE INDEX IF NOT EXISTS chunks_fts_gin ON chunks USING gin (fts);
-- Filter indexes.
CREATE INDEX IF NOT EXISTS chunks_spec_id  ON chunks (spec_id);
CREATE INDEX IF NOT EXISTS chunks_series   ON chunks (series);
CREATE INDEX IF NOT EXISTS chunks_release  ON chunks (release);
CREATE INDEX IF NOT EXISTS chunks_doc_type ON chunks (doc_type);
CREATE INDEX IF NOT EXISTS chunks_source   ON chunks (source);

CREATE TABLE IF NOT EXISTS meta (
  db_origin SMALLINT NOT NULL DEFAULT 0,
  key   TEXT NOT NULL,
  value TEXT,
  PRIMARY KEY (db_origin, key)
);

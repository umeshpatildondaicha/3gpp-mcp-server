#!/usr/bin/env python3
"""
Migrate the two SQLite knowledge bases into Postgres 16 + pgvector.

  pip install psycopg2-binary
  python3 migrate_sqlite_to_pg.py \
      --certified /path/to/3gpp_kb/3gpp_certified.db \
      --extras    /path/to/3gpp_kb/telecom_extras.db \
      --dsn "postgresql://kb:kb@localhost:5432/threegpp_kb"

Streams rows in batches (never loads all vectors into RAM), tags each row with
db_origin (0=certified, 1=extras), strips NUL bytes, validates the 1024-dim
vector, and rebuilds the HNSW + GIN indexes AFTER the bulk load for speed.
"""
import argparse, sqlite3, struct, io, sys
import psycopg2

BATCH = 2000
DIM = 1024
COLS = ["id","db_origin","spec_id","release","series","series_desc",
        "doc_type","source","title","text","chunk_index","total_chunks","embedding"]

def clean(s):
    return "" if s is None else str(s).replace("\x00", "")

def csv_field(s):
    # minimal CSV escaping for COPY ... WITH (FORMAT csv)
    s = "" if s is None else str(s)
    if any(c in s for c in [',', '"', '\n', '\r']):
        return '"' + s.replace('"', '""') + '"'
    return s

def rows_from_sqlite(path, origin):
    con = sqlite3.connect(f"file:{path}?mode=ro&immutable=1", uri=True)
    con.row_factory = sqlite3.Row
    cur = con.cursor()
    have = {r[1] for r in cur.execute("PRAGMA table_info(chunks)")}
    ecur = con.cursor()
    n = skip = 0
    for c in cur.execute("SELECT * FROM chunks"):
        e = ecur.execute("SELECT vector FROM embeddings WHERE chunk_id=?", (c["id"],)).fetchone()
        if not e:
            skip += 1; continue
        blob = e[0]
        if len(blob) // 4 != DIM:
            skip += 1; continue
        fl = struct.unpack(f"<{DIM}f", blob)
        vec = "[" + ",".join("%.6g" % x for x in fl) + "]"
        d = dict(c)
        yield [clean(d.get("id")), origin, clean(d.get("spec_id")), clean(d.get("release")),
               clean(d.get("series")), clean(d.get("series_desc")), clean(d.get("doc_type")),
               clean(d.get("source")), clean(d.get("title")), clean(d.get("text")),
               d.get("chunk_index"), d.get("total_chunks"), vec]
        n += 1
    con.close()
    print(f"  [{path}] streamed {n} rows, skipped {skip} (missing/mismatched vector)")

def copy_batches(pg, gen):
    cur = pg.cursor()
    buf = io.StringIO(); count = 0; total = 0
    def flush():
        nonlocal buf, count, total
        if count == 0: return
        buf.seek(0)
        cur.copy_expert("COPY chunks(%s) FROM STDIN WITH (FORMAT csv)" % ",".join(COLS), buf)
        total += count; print(f"  ...copied {total}", end="\r"); sys.stdout.flush()
        buf = io.StringIO(); count = 0
    for row in gen:
        buf.write(",".join(csv_field(x) for x in row) + "\n"); count += 1
        if count >= BATCH: flush()
    flush(); pg.commit(); print(f"  committed {total} rows total          ")
    return total

def load_meta(pg, path, origin):
    con = sqlite3.connect(f"file:{path}?mode=ro&immutable=1", uri=True)
    cur = pg.cursor()
    for k, v in con.execute("SELECT key, value FROM meta"):
        cur.execute("INSERT INTO meta(db_origin,key,value) VALUES(%s,%s,%s) "
                    "ON CONFLICT (db_origin,key) DO UPDATE SET value=EXCLUDED.value",
                    (origin, clean(k), clean(v)))
    pg.commit(); con.close()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--certified", required=True)
    ap.add_argument("--extras")
    ap.add_argument("--dsn", required=True)
    a = ap.parse_args()
    pg = psycopg2.connect(a.dsn)
    cur = pg.cursor()

    print("Dropping heavy indexes for fast bulk load ...")
    cur.execute("DROP INDEX IF EXISTS chunks_embedding_hnsw")
    cur.execute("DROP INDEX IF EXISTS chunks_fts_gin")
    cur.execute("TRUNCATE chunks; TRUNCATE meta;")
    pg.commit()

    print("Loading certified DB (origin 0) ...")
    copy_batches(pg, rows_from_sqlite(a.certified, 0)); load_meta(pg, a.certified, 0)
    if a.extras:
        print("Loading extras DB (origin 1) ...")
        copy_batches(pg, rows_from_sqlite(a.extras, 1)); load_meta(pg, a.extras, 1)

    print("Rebuilding indexes (HNSW build is the slow part) ...")
    cur.execute("SET maintenance_work_mem='1GB'")
    cur.execute("CREATE INDEX chunks_embedding_hnsw ON chunks "
                "USING hnsw (embedding vector_cosine_ops) WITH (m=16, ef_construction=64)")
    cur.execute("CREATE INDEX chunks_fts_gin ON chunks USING gin (fts)")
    pg.commit()
    cur.execute("ANALYZE chunks"); pg.commit()

    cur.execute("SELECT count(*), count(DISTINCT spec_id) FROM chunks")
    n, s = cur.fetchone()
    print(f"DONE. {n} chunks across {s} specs loaded into Postgres.")
    pg.close()

if __name__ == "__main__":
    main()

# Deployment — 3GPP MCP server

Consolidated 2026-07-30. **Exactly two databases**, both in `3gpp_kb/`.

    3gpp_kb/3gpp_certified.db    2.03 GB    98,236 chunks
    3gpp_kb/telecom_extras.db    0.65 GB   104,260 chunks
                                          ─────────────────
                                          202,496 vectors

BAAI/bge-m3, 1024-dim, L2-normalised, 4096-byte float32 vectors. Verified on
both files: zero chunks without a vector, zero vectors of the wrong length,
`PRAGMA quick_check = ok`.

## What is in each

`3gpp_certified.db` — 3GPP TS/TR prose (92,300) plus a parallel CM/OAM
extraction (5,936) marked `source='3gpp-cm'`:

| source | doc_type | chunks |
|---|---|---|
| (blank) | TR / TS | 82,734 |
| 3gpp | TS | 9,566 |
| 3gpp-cm | TS | 5,362 |
| 3gpp-cm | YANG | 432 |
| 3gpp-cm | OPENAPI | 142 |

The `3gpp-cm` rows are a **second extraction of specs the DB already covers**,
kept because they are not redundant: the YANG and OpenAPI renditions have no
equivalent in the prose-only corpus, and several specs are far denser there
(28.541 → 827 chunks vs 188; 28.550 → 74 vs 5; 24.008 → 1,144 vs 960). They also
appear to be newer — cm records `V20.0.0 (2026-06)` for 24.008 where the prose
side records `j50`. Both extractions can surface for one spec; `source` tells
them apart.

`telecom_extras.db`:

| series | chunks | |
|---|---|---|
| CISCO | 42,951 | IOS-XE YANG configuration model (17.6.1) |
| JUNIPER | 28,301 | Junos CLI Reference — statements + operational commands |
| NOKIA | 17,535 | SR OS YANG configuration model |
| MIB | 10,627 | SNMP MIB definitions — vendor and standard |
| RFC | 2,325 | IETF |
| ETSI-NFV / ITU-T / GSMA / O-RAN / MEF / TM-Forum | 2,521 | |

`3gpp_certified.db` also carries a clause-level index (`clauses`, 494,656 units)
used by `lookupIeDefinition`. Keep it — the server logs `clause-level index
present` when it finds it and silently degrades to 400-word chunks when it
does not.

## Run it

    ./start.sh dev     # mvn spring-boot:run
    ./start.sh build   # package a JAR, then run it

Both DB paths default correctly, so no env vars are needed. Override with
`KB_DB_PATH` / `KB_DB_PATH2`.

Vectors need ~830 MB of heap on their own. `start.sh` and `run.sh` set **no**
heap flags — set `-Xmx3g` explicitly in production. The k8s manifest requests
3Gi / limits 8Gi and mounts both DBs from PVC `3gpp-db-pvc` at
`/var/lib/3gpp-kb`; **the Docker image does not contain the databases.**

First start downloads BGE-M3 and the reranker from HuggingFace into `~/.cache/`.

## Chunk id namespaces

Ids are **not** from one scheme. The 3GPP/extras pipeline uses
`sha256(spec_id + id_suffix + start + text[:80])[:32]`; the vendor pipelines use
the raw YANG path (`acl#0`, `acl__acl-set`). Cisco and Nokia both implement the
same OpenConfig modules, so 1,007 of those paths collided with entirely
different text on each side, and `INSERT OR IGNORE` would have dropped 1,007
Cisco rows silently. Merged corpora therefore carry a prefix:

    cisco:   Cisco IOS-XE YANG
    mib:     SNMP MIB corpus
    cm:      3GPP CM/OAM extraction
    bx:      residue from an interrupted extras rebuild

**Anything merging into these DBs must keep the prefixes.** Drop them and the
next merge loses rows without logging anything.

## Known limits — read before filing a bug

**camelCase MIB identifiers do not tokenise.** The MIB corpus is present and
searchable, but `jnxVpnPwDown` is one token; a query written `Jnx Vpn Pw Down`
scores ~0.06 and looks like a miss. Query MIB objects in their native
camelCase, or by their description text.

**Ten of eighteen 3GPP series are not indexed** — 21, 22, 25, 26, 27, 31, 34,
35, 37, 45. `listSeries` still advertises all eighteen because it reads
`retrieval/series-catalog.tsv`, so a series filter on those ten returns nothing
rather than an explanatory error.

**Five specs the scope gate names as topic owners are absent**: 29.244 (PFCP),
32.298 (CDR), 32.240 (charging), 24.229 (IMS SIP), 29.281 (GTP-U).

**~74 specs are title-only stubs.** In the 3GPP half these are extraction
failures (38.460–38.463 and other CU/DU interface specs). Every `ITU-T-X.*` and
`ITU-T-M.*` entry in extras is a one-line placeholder — those are paywalled and
need a TIES/MyITU account.

**`3gpp_certified.db` still carries 7,301 binary chunks (7.9%)** — OLE compound
file headers and EMF metafile records pulled out of `.doc` sources — plus ~1,400
Word TOC field-code chunks. The prune that produced this DB was incomplete. They
are noise, not a correctness risk, but they dilute retrieval.

**Nokia paths differ from audit-tool paths.** A config-audit `moHierarchy` and
the vendor's own model path routinely disagree — `grt-lookup` vs `grt-leaking`,
`router/policy-options` vs `policy-options`. Query the parameter NAME, not the
path. This is written into the `search3gpp` tool description and
`docs/ORCHESTRATOR_PROMPT.md`.

## UI settings that matter

`ui/server.py` reads these from the environment:

    UI_AGENT_MAX_STEPS=15      tool-calling rounds before giving up
    UI_TOOL_RESULT_MAX=6000    chars per tool result kept in context
    UI_AGENT_TEMPERATURE=0     defaults to 0 — do not raise it

The tool-result cap must stay well above one chunk (~2,500 chars). Cutting it to
1500 made the agent loop: it never received a whole chunk, so it kept rephrasing
the same query. The agent also short-circuits a repeated `(tool, arguments)`
pair instead of re-running it.

## Superseded databases

`_to_delete/superseded-dbs/` (3.3 GB) holds six files removed during
consolidation. Each was proved to contribute nothing:

- `juniper.db`, `nokia.db`, `cisco.db` — md5-identical to the copies under the
  external `REFRANCE …/VENDOR/` corpus, and fully contained in
  `telecom_extras.db`.
- `3gpp-mcp-server__telecom_extras.db` (3,761) — a strict subset.
- `3gpp embedings__3gpp_certified.db` (125,692) — the **unpruned** artifact:
  every one of its 33,392 extra rows is binary garbage, and it holds zero
  legitimate content the canonical DB lacks.
- `3gpp embedings__telecom_extras.db` (7,424) — an interrupted rebuild; its 358
  unique rows were merged in.

Safe to delete once the deployment is confirmed good.

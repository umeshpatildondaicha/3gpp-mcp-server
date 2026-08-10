# Gold-set audit — `bench/questions_broad.py`

**Date:** 2026-07-27
**Why:** a wrong `expected` set silently corrupts the benchmark. It either punishes a
correct retrieval or rewards a wrong one, and either way every before/after comparison
built on it is misleading. Before drawing conclusions from the 100-question run, all 50
broad questions were audited against the **actual chunk text** in `3gpp_certified.db`.

**Method:** spec titles could not be used — 96% of the corpus carried the placeholder
`"3GPP TS <id>"` at audit time. Every judgement below is made from body content: the
Scope clause, the table of contents, or the clause text itself.

**A caution that mattered:** the 28/29/32-series `text` column begins with an injected
metadata header repeating a `Title:` string, and **some of those headers are wrong** —
32.425's header says "Subscriber and Equipment Trace" while its body is E-UTRAN
performance counters; 28.545's says "Management and Orchestration of Virtualised
Resources" while its body is Fault Supervision. Headers were not trusted.

---

## Corrections applied

### False gold — the expected spec does not cover the topic

**"IMS emergency session establishment"** · `{23.228}` → `{23.167}`
23.167 TOC: *"6.2.2 Emergency-CSCF … 6.2.6 Emergency Access Transfer Function (EATF) …
7 Procedures related to establishment of IMS emergency sessions"*, body: *"The
establishment of IMS emergency sessions shall be possible for users with a barred public
user identity."* 23.228's 16 "emergency" chunks are TOC lines and cross-references only.
**23.167 was being returned at rank 1 and scored a MISS.**

**"UDM UDR subscription data types"** · `{29.571, 23.501}` → `{23.501, 23.502}`
29.571 in this corpus is raw SBI OpenAPI YAML — **0 chunks mention UDR**, 0 mention
"subscription data", 3 mention UDM. Sample: *"$ref: '#/components/schemas/UeAuth'"*.
The real spec (29.505/29.519) is absent. 23.501 has 224 UDM / 60 UDR / 119
"subscription data" chunks; 23.502 has 142.

**"EPS AKA authentication vector generation"** · `{33.401, 24.301}` → `{33.401}`
24.301 has exactly **one** "authentication vector" chunk, about the identification
procedure after an AUTHENTICATION FAILURE — not AV generation. 33.401: *"6.1.1 AKA
procedure … Authentication data in this clause stands for EPS Authentication vector(s)."*

**"network slice subnet instance NSSI management"** · `{28.541, 28.530, 28.545}` → `{28.541, 28.530}`
28.545's body is Fault Supervision; its NSSI mentions are alarm handling
(*"5.1.11 Acknowledge alarms of NSSI … 5.1.12 Clear alarms of NSSI"*). Including it let
an alarm-only retrieval score as a hit. (The real NSSI provisioning spec, 28.531, is absent.)

### Missing gold — a correct retrieval was scored as a miss

**"AMF UE context management over N2"** · added `23.502`
23.502 has 86 chunks matching `UE Context AND N2` vs 23.501's 43 — the N2 UE-context
stage-2 flows live there.

**"Xn handover procedure between gNBs"** · added `38.401`
38.401 clause 8 carries the overall Xn-based inter-NG-RAN mobility procedures
(*"8.15.2 Mobility procedure for Multicast / 8.15.2.1 Inter-gNB-CU Mobility"*).

### Ungrounded — no valid answer in the corpus, question replaced

**"charging data record file format and transfer"** · was `{32.692}`
32.692 chunk 5: *"Telecommunication management; Inventory Management (IM) network
resources Integration Reference Point (IRP)"* — Inventory Management, not charging.
The corpus 32-series is 32.111-1, 32.111-2, 32.302, 32.312, 32.401, 32.404, 32.421,
32.425, 32.600, 32.690, 32.692 — **no CDR spec at all**. (32.692 is also a poor target
regardless: only ~23 of its 79 chunks are readable text.)
→ replaced with **"subscriber and equipment trace session activation"** · `{32.421}`
32.421 Scope: *"requirements for the management of Trace and the reporting of Trace data
… as it refers to subscriber tracing"*.

**"MMTel supplementary services architecture"** · was `{23.228}`
24.173 and 22.173 are both absent. 23.228's 8 "supplementary service" chunks are
incidental reference-list entries; its 21 MMTel chunks are the Rel-19 IMS Data Channel
annex. 23.392 exists but is the Rel-19 "MMTel Enabler", a different subject.
→ replaced with **"IMS service continuity PS-CS access transfer SRVCC"** · `{23.237, 23.216}`
23.237 TOC: *"4.1.1 PS-CS Access Transfer / 4.1.2 PS-PS Access Transfer"*;
23.216 TOC: *"4.1.2 Architectural Principles for SRVCC and vSRVCC to 3GPP UTRAN/GERAN"*.

---

## Noted, no change

**"service based interface HTTP/2 in 5G core"** · `{29.500, 23.501}` kept
23.501 has **0** chunks containing the literal "HTTP/2" (25 for "service-based interface");
29.500 carries all 52. Kept as-is because 23.501 legitimately owns the SBI-architecture
half of a `crossspec` question — but it cannot answer the HTTP/2 half.

Everything else verified correctly grounded. The 24-series (24.301/24.501), 33-series
(33.401/33.501) and 29-series (29.274/29.500/29.510/29.571) gold sets were effectively
forced by what is indexed, and are right.

---

## Effect on the numbers

Re-scoring **both** the before and after runs on the corrected gold set moved the
absolute scores (63/100 → 64/98 for the baseline, after excluding the two ungrounded
questions) but **did not change the before/after delta**, which was zero either way.
The correction changed the yardstick, not the conclusion.

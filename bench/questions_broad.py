"""
50-question BROAD retrieval benchmark for the 3gpp-mcp-server.

Complements benchmark_oam.py (which is OAM/PM/FM/CM-only). This suite exercises
the areas the TejVox competitive analysis flagged as gaps — NR radio, 5GC
architecture & procedures, NAS, security, LTE/EPC, IMS — i.e. the multi-spec
"procedure" territory where cross-spec synthesis, release-awareness and the
confidence gate are expected to matter.

Every `expected` spec was verified present in 3gpp_certified.db with >5 chunks
before being used, so a MISS is a retrieval failure, not a corpus gap.

`topic` buckets the question; `kind` marks what the question stresses:
  lookup    — a single spec plainly owns the answer
  procedure — the answer spans a stage-2 flow (and often a stage-3 protocol spec)
  crossspec — the answer legitimately lives in two or more specs
"""

QUESTIONS = [
    # ── NR radio access (38.xxx) ──────────────────────────────────────────────
    {"q": "RRC connection setup procedure in NR",                 "expected": {"38.331"},                     "topic": "NR",   "kind": "procedure"},
    {"q": "bandwidth part BWP configuration",                     "expected": {"38.331", "38.213"},           "topic": "NR",   "kind": "lookup"},
    {"q": "SSB synchronization signal block structure",           "expected": {"38.213", "38.211", "38.300"}, "topic": "NR",   "kind": "crossspec"},
    {"q": "random access procedure msg1 msg2 msg3 NR",            "expected": {"38.321", "38.213", "38.300"}, "topic": "NR",   "kind": "procedure"},
    {"q": "MAC HARQ operation in NR",                             "expected": {"38.321"},                     "topic": "NR",   "kind": "lookup"},
    {"q": "logical channel prioritization NR MAC",                "expected": {"38.321"},                     "topic": "NR",   "kind": "lookup"},
    {"q": "NG-RAN architecture gNB-CU gNB-DU functional split",   "expected": {"38.401", "38.300"},           "topic": "NR",   "kind": "crossspec"},
    {"q": "F1 setup procedure between gNB-DU and gNB-CU",         "expected": {"38.473", "38.401"},           "topic": "NR",   "kind": "procedure"},
    {"q": "Xn handover procedure between gNBs", "expected": {"38.423", "38.300", "38.401"}, "topic": "NR", "kind": "procedure"},
    {"q": "NGAP initial context setup request",                   "expected": {"38.413"},                     "topic": "NR",   "kind": "procedure"},
    {"q": "PDCCH search space and CORESET configuration",         "expected": {"38.213", "38.331"},           "topic": "NR",   "kind": "lookup"},
    {"q": "CSI reporting configuration NR",                       "expected": {"38.214", "38.331"},           "topic": "NR",   "kind": "lookup"},

    # ── 5G core architecture & procedures (23.5xx / 29.5xx) ───────────────────
    {"q": "5G system architecture network functions",             "expected": {"23.501"},                     "topic": "5GC",  "kind": "lookup"},
    {"q": "PDU session establishment procedure",                  "expected": {"23.502", "24.501"},           "topic": "5GC",  "kind": "procedure"},
    {"q": "UE initial registration procedure in 5GS",             "expected": {"23.502", "24.501"},           "topic": "5GC",  "kind": "procedure"},
    {"q": "service based interface HTTP/2 in 5G core",            "expected": {"29.500", "23.501"},           "topic": "5GC",  "kind": "crossspec"},
    {"q": "NRF network function discovery procedure",             "expected": {"29.510", "23.502"},           "topic": "5GC",  "kind": "procedure"},
    {"q": "UDM UDR subscription data types", "expected": {"23.501", "23.502"}, "topic": "5GC", "kind": "lookup"},
    {"q": "network slice selection NSSF S-NSSAI",                 "expected": {"23.501", "23.502"},           "topic": "5GC",  "kind": "crossspec"},
    {"q": "QoS flow and 5QI QoS profile in 5GS",                  "expected": {"23.501", "23.503"},           "topic": "5GC",  "kind": "crossspec"},
    {"q": "PCF policy association establishment",                 "expected": {"23.503", "23.502"},           "topic": "5GC",  "kind": "procedure"},
    {"q": "AMF UE context management over N2", "expected": {"23.501", "23.502", "38.413"}, "topic": "5GC", "kind": "crossspec"},
    {"q": "SMF role in session management",                       "expected": {"23.501"},                     "topic": "5GC",  "kind": "lookup"},
    {"q": "UE configuration update procedure 5GS",                "expected": {"23.502", "24.501"},           "topic": "5GC",  "kind": "procedure"},

    # ── NAS signalling (24.xxx) ───────────────────────────────────────────────
    {"q": "5GMM registration management states",                  "expected": {"24.501", "23.501"},           "topic": "NAS",  "kind": "lookup"},
    {"q": "NAS security mode command procedure",                  "expected": {"24.501", "33.501"},           "topic": "NAS",  "kind": "procedure"},
    {"q": "service request procedure 5G NAS",                     "expected": {"24.501", "23.502"},           "topic": "NAS",  "kind": "procedure"},
    {"q": "EMM attach procedure in LTE",                          "expected": {"24.301", "23.401"},           "topic": "NAS",  "kind": "procedure"},
    {"q": "tracking area update procedure",                       "expected": {"24.301", "23.401"},           "topic": "NAS",  "kind": "procedure"},

    # ── Security (33.xxx) ─────────────────────────────────────────────────────
    {"q": "5G AKA authentication procedure",                      "expected": {"33.501"},                     "topic": "SEC",  "kind": "procedure"},
    {"q": "SUCI SUPI subscription identifier concealment",        "expected": {"33.501", "23.003"},           "topic": "SEC",  "kind": "crossspec"},
    {"q": "5G key hierarchy KAUSF KSEAF KAMF",                    "expected": {"33.501"},                     "topic": "SEC",  "kind": "lookup"},
    {"q": "EPS AKA authentication vector generation", "expected": {"33.401"}, "topic": "SEC", "kind": "lookup"},
    {"q": "SEPP N32 inter-PLMN interconnect security",            "expected": {"33.501"},                     "topic": "SEC",  "kind": "lookup"},

    # ── LTE / EPC (23.4xx, 36.xxx, 29.274) ────────────────────────────────────
    {"q": "dedicated EPS bearer establishment",                   "expected": {"23.401", "24.301"},           "topic": "LTE",  "kind": "procedure"},
    {"q": "S1AP initial UE message",                              "expected": {"36.413"},                     "topic": "LTE",  "kind": "procedure"},
    {"q": "LTE RRC measurement report configuration",             "expected": {"36.331"},                     "topic": "LTE",  "kind": "lookup"},
    {"q": "X2 based handover in LTE",                             "expected": {"23.401", "36.300"},           "topic": "LTE",  "kind": "procedure"},
    {"q": "GTPv2-C create session request message",               "expected": {"29.274"},                     "topic": "LTE",  "kind": "procedure"},
    {"q": "SGW and PGW selection function",                       "expected": {"23.401"},                     "topic": "LTE",  "kind": "lookup"},
    {"q": "LTE MAC random access procedure",                      "expected": {"36.321", "36.300"},           "topic": "LTE",  "kind": "procedure"},
    {"q": "untrusted non-3GPP access via ePDG",                   "expected": {"23.402"},                     "topic": "LTE",  "kind": "lookup"},

    # ── IMS (23.228) ──────────────────────────────────────────────────────────
    {"q": "IMS registration with P-CSCF and S-CSCF",              "expected": {"23.228"},                     "topic": "IMS",  "kind": "procedure"},
    {"q": "IMS emergency session establishment", "expected": {"23.167"}, "topic": "IMS", "kind": "procedure"},
    {"q": "IMS service continuity PS-CS access transfer SRVCC", "expected": {"23.237", "23.216"}, "topic": "IMS", "kind": "procedure"},

    # ── Identifiers, OAM anchors, charging ────────────────────────────────────
    {"q": "network slice subnet instance NSSI management", "expected": {"28.541", "28.530"}, "topic": "MGMT", "kind": "crossspec"},
    {"q": "5G performance measurement definitions",               "expected": {"28.552"},                     "topic": "MGMT", "kind": "lookup"},
    {"q": "alarm notification integration reference point",       "expected": {"32.111-2"},                   "topic": "MGMT", "kind": "lookup"},
    {"q": "IMSI GUTI subscriber identifier formats",              "expected": {"23.003"},                     "topic": "MGMT", "kind": "lookup"},
    {"q": "subscriber and equipment trace session activation",     "expected": {"32.421"},                     "topic": "MGMT", "kind": "lookup"},
]

assert len(QUESTIONS) == 50, f"need 50, got {len(QUESTIONS)}"

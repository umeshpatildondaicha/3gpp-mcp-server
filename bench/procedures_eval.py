"""
Evaluation set for getProcedureFlow (cross-spec procedure synthesis).

This tool shipped unmeasured, unlike every other change in this benchmark suite.
It answers a different question from search3gpp — not "is the top spec right"
but "does the returned bundle contain the specs you need to assemble the flow,
without padding it with specs you don't".

Three metrics, all computed by run_procflow.py:

  coverage  — fraction of `expected` specs present anywhere in the bundle.
              This is the point of the tool: a procedure spans layers, and a
              bundle missing the stage-3 spec cannot support a cited flow.
  noise     — fraction of returned specs that are `never` specs: study reports
              (TRs) and UE conformance-test specs. A conformance spec describes
              how to TEST a procedure, never how the procedure works, so citing
              one in a call flow is always wrong.
  honesty   — whether layers with no strong evidence are reported as empty
              rather than padded with low-scoring hits. The tool instructs the
              caller to "say so rather than inventing that leg of the flow";
              that instruction is only truthful if the tool itself abstains.

Every `expected` spec was verified present in 3gpp_certified.db.
`technology` is passed through so LTE procedures don't get scored on 5G layers.
"""

# UE conformance / test specification families. These are legitimate hits for
# other queries but never the right citation for how a procedure works.
TEST_SPEC_PREFIXES = (
    "36.508", "36.509", "36.521", "36.523", "36.579",
    "37.571", "38.508", "38.509", "38.521", "38.523", "38.533",
    "34.123", "34.229",
)

PROCEDURES = [
    {"procedure": "UE initial registration", "technology": "5G",
     "expected": {"23.502", "24.501"}, "bonus": {"33.501", "38.413"}},

    {"procedure": "PDU session establishment", "technology": "5G",
     "expected": {"23.502", "24.501"}, "bonus": {"23.501", "38.413"}},

    {"procedure": "5G AKA authentication", "technology": "5G",
     "expected": {"33.501", "24.501"}, "bonus": {"23.502"}},

    {"procedure": "service request", "technology": "5G",
     "expected": {"24.501", "23.502"}, "bonus": {"38.413"}},

    {"procedure": "Xn based handover", "technology": "5G",
     "expected": {"38.423"}, "bonus": {"38.300", "38.401", "23.502"}},

    {"procedure": "RRC connection establishment", "technology": "5G",
     "expected": {"38.331"}, "bonus": {"38.300"}},

    {"procedure": "EPS attach", "technology": "LTE",
     "expected": {"24.301", "23.401"}, "bonus": {"36.413"}},

    {"procedure": "tracking area update", "technology": "LTE",
     "expected": {"24.301", "23.401"}, "bonus": set()},

    {"procedure": "X2 based handover", "technology": "LTE",
     "expected": {"36.423"}, "bonus": {"36.300", "23.401"}},

    {"procedure": "IMS registration", "technology": "both",
     "expected": {"23.228"}, "bonus": {"23.167", "23.237"}},
]

assert len(PROCEDURES) == 10, f"expected 10 procedures, got {len(PROCEDURES)}"


def is_test_spec(spec_id):
    head = spec_id.split("-")[0]
    return any(spec_id.startswith(p) or head == p for p in TEST_SPEC_PREFIXES)

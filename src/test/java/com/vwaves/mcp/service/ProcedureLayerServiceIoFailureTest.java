package com.vwaves.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vwaves.mcp.config.RetrievalProperties;
import org.junit.jupiter.api.Test;

/**
 * The {@link ProcedureLayerService} branches its main test could not reach
 * with real files: IO failures while reading EXISTING layer and synonym
 * tables. Both loaders must degrade to empty tables, never fail startup.
 */
class ProcedureLayerServiceIoFailureTest {

    private static RetrievalProperties props() {
        return new RetrievalProperties(
                5, 1, 4,                 // per-spec caps
                40, 10, 300, 4, 200,     // pool sizes
                0.15, 1.5, 60, 0.5, 0.5, 1.3,   // scoring
                1.25, 0.85,              // release
                10,                      // agreement top-N
                0.12, 0.25, 0.02,        // confidence margins
                2, 1500,                 // stub suppression
                4,                       // and-term limit
                700, 1000,               // study-report range
                "classpath:retrieval/stop-words.txt",
                "classpath:retrieval/and-term-subst.tsv",
                "classpath:retrieval/non-3gpp-intent-terms.txt",
                "classpath:retrieval/spec-ownership.tsv",
                0.30, 0.45, "classpath:retrieval/test-spec-prefixes.txt",
                "stub:procedure-layers.tsv",
                "stub:procedure-synonyms.tsv",
                "classpath:retrieval/series-catalog.tsv", 1.35,
                "classpath:retrieval/intents.tsv",
                "classpath:retrieval/intent-exemplars.tsv", 0.50);
    }

    @Test
    void midReadIoFailureYieldsEmptyTablesWithoutThrowing() {
        ProcedureLayerService s = new ProcedureLayerService(
                new FailingResourceSupport.FailingResourceLoader(), props());

        assertThat(s.layerCount()).isZero();
        assertThat(s.synonymCount()).isZero();
        assertThat(s.layersFor("5g")).isEmpty();
        assertThat(s.layersFor("both")).isEmpty();
        // No synonym table → expansion is the identity function.
        assertThat(s.expandProcedure("registration")).isEqualTo("registration");
        // No alias table → everything canonicalises to the 5g fallback.
        assertThat(s.canonicalTechnology("lte")).isEqualTo("5g");
    }
}

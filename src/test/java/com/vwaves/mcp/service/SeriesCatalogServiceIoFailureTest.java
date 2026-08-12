package com.vwaves.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vwaves.mcp.config.RetrievalProperties;
import org.junit.jupiter.api.Test;

/**
 * The {@link SeriesCatalogService} branch its main test could not reach with
 * real files: an IO failure while reading an existing catalogue. listSeries
 * must degrade to an empty catalogue, never fail startup.
 */
class SeriesCatalogServiceIoFailureTest {

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
                "classpath:retrieval/procedure-layers.tsv",
                "classpath:retrieval/procedure-synonyms.tsv",
                "stub:series-catalog.tsv", 1.35,
                "classpath:retrieval/intents.tsv",
                "classpath:retrieval/intent-exemplars.tsv", 0.50);
    }

    @Test
    void midReadIoFailureYieldsAnEmptyCatalogueWithoutThrowing() {
        SeriesCatalogService s = new SeriesCatalogService(
                new FailingResourceSupport.FailingResourceLoader(), props());

        assertThat(s.entries()).isEmpty();
        assertThat(s.describe("23")).isEmpty();
    }
}

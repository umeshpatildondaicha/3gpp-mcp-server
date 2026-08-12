package com.vwaves.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vwaves.mcp.config.RetrievalProperties;
import org.junit.jupiter.api.Test;

/**
 * The one {@link LexiconService} branch the edge tests could not reach with
 * real files: an IO failure while READING an existing lexicon (as opposed to a
 * missing file). Both loaders — the token-set loader and the TSV loader — must
 * degrade to empty lexicons rather than fail construction.
 */
class LexiconServiceIoFailureTest {

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
                "stub:stop-words.txt",
                "stub:and-term-subst.tsv",
                "stub:non-3gpp-intent-terms.txt",
                "classpath:retrieval/spec-ownership.tsv",
                0.30, 0.45, "stub:test-spec-prefixes.txt",
                "classpath:retrieval/procedure-layers.tsv",
                "classpath:retrieval/procedure-synonyms.tsv",
                "classpath:retrieval/series-catalog.tsv", 1.35,
                "classpath:retrieval/intents.tsv",
                "classpath:retrieval/intent-exemplars.tsv", 0.50);
    }

    @Test
    void midReadIoFailureDegradesEveryLexiconToEmptyWithoutThrowing() {
        LexiconService lexicon = new LexiconService(
                new FailingResourceSupport.FailingResourceLoader(), props());

        assertThat(lexicon.stopWords()).isEmpty();
        assertThat(lexicon.andTermSubst()).isEmpty();
        assertThat(lexicon.non3gppIntentTerms()).isEmpty();
        // With no prefixes loaded, nothing can classify as a test spec.
        assertThat(lexicon.isTestSpec("36.523-1")).isFalse();
        assertThat(lexicon.isTestSpec("36.523")).isFalse();
    }
}

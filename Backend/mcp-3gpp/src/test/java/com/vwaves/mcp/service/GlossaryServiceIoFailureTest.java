package com.vwaves.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The {@link GlossaryService} branch {@code GlossaryServiceTest} could not
 * reach with real files: an IO failure while reading an existing glossary.
 * Query expansion must degrade to the identity function, never fail startup.
 */
class GlossaryServiceIoFailureTest {

    @Test
    void midReadIoFailureDisablesExpansionWithoutThrowing() {
        GlossaryService g = new GlossaryService(
                new FailingResourceSupport.FailingResourceLoader(), "stub:vocab.tsv");

        assertThat(g.size()).isZero();
        assertThat(g.expand("what does the AMF do")).isEqualTo("what does the AMF do");
    }
}

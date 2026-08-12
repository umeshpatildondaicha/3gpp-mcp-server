package com.vwaves.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vwaves.mcp.config.RetrievalProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Covers the {@link LexiconService} paths the existing tests leave out:
 * the file parsers (comments, blank lines, case-folding, malformed TSV rows),
 * the missing-file degradation branch, and {@link LexiconService#isTestSpec}.
 *
 * <p>Unlike {@code VendorAliasLexiconTest} (which pins the REAL classpath
 * lexicons), these tests feed the loader synthetic {@code file:} resources so
 * every parser branch is exercised deterministically.
 */
class LexiconServiceEdgeTest {

    @TempDir
    Path dir;

    private LexiconService lexicon;

    @BeforeEach
    void setUp() throws IOException {
        // \t and \s (space) are ordinary escape sequences inside text blocks.
        Files.writeString(dir.resolve("stop-words.txt"), """
                # comment line must be ignored
                The

                  FOR\s\s
                is
                # another comment
                """);
        Files.writeString(dir.resolve("subst.tsv"), """
                # vendor → canonical
                VoLTE\tMMTel
                line-without-a-tab-is-skipped
                SIB1\tsi-Periodicity SIB1
                  spaced  \t  Canonical Value\s\s
                """);
        Files.writeString(dir.resolve("prefixes.txt"), """
                36.523
                ABC
                RFC-FULL-9999
                """);
        // non-3gpp-intent-terms deliberately points at a missing file: the
        // service must degrade to an empty lexicon, not throw.
        lexicon = new LexiconService(new DefaultResourceLoader(), props(
                "file:" + dir.resolve("stop-words.txt"),
                "file:" + dir.resolve("subst.tsv"),
                "file:" + dir.resolve("does-not-exist.txt"),
                "file:" + dir.resolve("prefixes.txt")));
    }

    private static RetrievalProperties props(String stopWords, String subst,
                                             String non3gpp, String testPrefixes) {
        return new RetrievalProperties(
                3, 3, 3,
                60, 4, 400, 3, 10,
                0.0, 0.05, 60, 0.85, 0.5, 1.0,
                0.05, 0.9,
                10,
                0.12, 0.25, 0.02,
                2, 400,
                4,
                700, 800,
                stopWords,
                subst,
                non3gpp,
                "classpath:retrieval/spec-ownership.tsv",
                0.30, 0.45, testPrefixes,
                "classpath:retrieval/procedure-layers.tsv",
                "classpath:retrieval/procedure-synonyms.tsv",
                "classpath:retrieval/series-catalog.tsv", 1.35,
                "classpath:retrieval/intents.tsv",
                "classpath:retrieval/intent-exemplars.tsv", 0.50);
    }

    // ── token-file parsing ───────────────────────────────────────────────────

    @Nested
    class TokenFileParsing {

        @Test
        void stripsCommentsBlanksAndWhitespaceAndLowercases() {
            assertThat(lexicon.stopWords()).isEqualTo(Set.of("the", "for", "is"));
        }

        @Test
        void missingFileDegradesToEmptySetWithoutThrowing() {
            assertThat(lexicon.non3gppIntentTerms()).isEmpty();
        }
    }

    // ── TSV parsing ──────────────────────────────────────────────────────────

    @Nested
    class TsvParsing {

        @Test
        void keyAndValueAreLowercasedAndTrimmed() {
            assertThat(lexicon.andTermSubst()).containsEntry("volte", "mmtel");
            assertThat(lexicon.andTermSubst()).containsEntry("spaced", "canonical value");
        }

        @Test
        void valueMayContainSpaces_splitIsOnFirstTabOnly() {
            // Multi-word canonical expansions must survive intact.
            assertThat(lexicon.andTermSubst()).containsEntry("sib1", "si-periodicity sib1");
        }

        @Test
        void malformedAndCommentRowsAreDropped() {
            assertThat(lexicon.andTermSubst()).hasSize(3);
            assertThat(lexicon.andTermSubst())
                    .doesNotContainKey("line-without-a-tab-is-skipped");
        }
    }

    // ── isTestSpec ───────────────────────────────────────────────────────────

    @Nested
    class TestSpecDetection {

        @Test
        void matchesTheFamilyPrefixBeforeTheDash() {
            assertThat(lexicon.isTestSpec("36.523-1")).isTrue();
            assertThat(lexicon.isTestSpec("36.523-3")).isTrue();
            assertThat(lexicon.isTestSpec("36.523")).isTrue();
        }

        @Test
        void matchesCaseInsensitivelyOnBothSides() {
            // File entry "ABC" is lower-cased on load; the incoming spec id is
            // lower-cased at lookup.
            assertThat(lexicon.isTestSpec("abc-1")).isTrue();
            assertThat(lexicon.isTestSpec("ABC-2")).isTrue();
        }

        @Test
        void fallsBackToTheFullSpecIdWhenThePrefixAloneIsNotListed() {
            // "RFC-FULL-9999" is listed as a FULL id. Its dash-head "rfc" is NOT
            // in the set, so only the exact-id branch can match it.
            assertThat(lexicon.isTestSpec("RFC-FULL-9999")).isTrue();
            assertThat(lexicon.isTestSpec("rfc-full-9999")).isTrue();
            // A different member of the same "family" must not match — the head
            // "rfc" alone is not a listed prefix.
            assertThat(lexicon.isTestSpec("RFC-FULL-1234")).isFalse();
        }

        @Test
        void unrelatedSpecsAndNullAreNotTestSpecs() {
            assertThat(lexicon.isTestSpec("38.331")).isFalse();
            assertThat(lexicon.isTestSpec(null)).isFalse();
        }
    }
}

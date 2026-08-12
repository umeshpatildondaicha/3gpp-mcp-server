package com.vwaves.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * The glossary rewrites every user query before retrieval, so its failure modes
 * are on both sides:
 *   - a MISSED expansion just means BM25 sees only the abbreviation (old behaviour)
 *   - a WRONG expansion (matching inside a word, expanding twice, mangling the
 *     original text) actively pollutes the query the retrievers run
 * The original query text must always survive verbatim as a prefix of the output.
 */
class GlossaryServiceTest {

    @TempDir
    Path tempDir;

    /** Build a service from an inline TSV, bypassing the shipped 3gpp-vocab.tsv. */
    private GlossaryService service(String tsvContent) throws Exception {
        Path tsv = tempDir.resolve("vocab.tsv");
        Files.writeString(tsv, tsvContent);
        return new GlossaryService(new DefaultResourceLoader(), tsv.toUri().toString());
    }

    private static final String VOCAB = """
            # comment line — must be ignored
            AMF\tAccess and Mobility Management Function
            SMF\tSession Management Function
            NG-RAN\tNext Generation Radio Access Network
            nssf\tNetwork Slice Selection Function

            malformed line without a tab
            EMPTYVAL\t
            """;

    @Nested
    @DisplayName("expansion behaviour")
    class Expansion {

        @Test
        void appendsExpansionAfterTheOriginalQuery() throws Exception {
            GlossaryService g = service(VOCAB);
            assertEquals("what does the AMF do Access and Mobility Management Function",
                    g.expand("what does the AMF do"));
        }

        @Test
        void matchIsCaseInsensitiveBothWays() throws Exception {
            GlossaryService g = service(VOCAB);
            // lowercase query token vs uppercase key...
            assertEquals("amf selection Access and Mobility Management Function",
                    g.expand("amf selection"));
            // ...and lowercase key in the file vs uppercase query token.
            assertEquals("NSSF discovery Network Slice Selection Function",
                    g.expand("NSSF discovery"));
        }

        @Test
        void punctuationAroundTheTokenIsStripped() throws Exception {
            GlossaryService g = service(VOCAB);
            assertEquals("AMF, then what? Access and Mobility Management Function",
                    g.expand("AMF, then what?"));
        }

        @Test
        void hyphenatedAbbreviationsSurviveCleaning() throws Exception {
            GlossaryService g = service(VOCAB);
            assertEquals("NG-RAN split Next Generation Radio Access Network",
                    g.expand("NG-RAN split"));
        }

        @Test
        void eachAbbreviationExpandsAtMostOnce() throws Exception {
            GlossaryService g = service(VOCAB);
            assertEquals("AMF to AMF handover Access and Mobility Management Function",
                    g.expand("AMF to AMF handover"));
        }

        @Test
        void multipleAbbreviationsExpandInQueryOrder() throws Exception {
            GlossaryService g = service(VOCAB);
            assertEquals("SMF selects AMF Session Management Function"
                            + " Access and Mobility Management Function",
                    g.expand("SMF selects AMF"));
        }

        @Test
        void unknownTokensLeaveTheQueryUntouched() throws Exception {
            GlossaryService g = service(VOCAB);
            assertEquals("pdu session establishment", g.expand("pdu session establishment"));
        }
    }

    @Nested
    @DisplayName("token eligibility limits")
    class TokenLimits {

        @Test
        void singleCharacterTokensNeverMatch() throws Exception {
            // A one-letter key would fire on nearly every query; the length floor
            // is what prevents that, so it must hold even when the key exists.
            GlossaryService g = service("A\tAmpere\nX\tUnknown\n");
            assertEquals("a x marks", g.expand("a x marks"));
        }

        @Test
        void tokensLongerThanTwelveCharactersNeverMatch() throws Exception {
            GlossaryService g = service("ABCDEFGHIJKLM\tThirteen letters\n");
            assertEquals("abcdefghijklm test", g.expand("abcdefghijklm test"));
        }

        @Test
        void twoAndTwelveCharacterTokensAreBothEligible() throws Exception {
            GlossaryService g = service("UE\tUser Equipment\nABCDEFGHIJKL\tTwelve\n");
            assertEquals("UE User Equipment", g.expand("UE"));
            assertEquals("ABCDEFGHIJKL Twelve", g.expand("ABCDEFGHIJKL"));
        }
    }

    @Nested
    @DisplayName("degenerate queries")
    class DegenerateQueries {

        @Test
        void nullBecomesEmptyString() throws Exception {
            assertEquals("", service(VOCAB).expand(null));
        }

        @Test
        void blankQueryIsReturnedAsIs() throws Exception {
            assertEquals("   ", service(VOCAB).expand("   "));
        }
    }

    @Nested
    @DisplayName("glossary file parsing")
    class FileParsing {

        @Test
        void commentsBlanksAndMalformedLinesAreSkipped() throws Exception {
            // VOCAB has 4 valid entries; the comment, blank, tab-less and
            // empty-value lines must not count.
            assertEquals(4, service(VOCAB).size());
        }

        @Test
        void missingFileDisablesExpansionInsteadOfFailing() {
            GlossaryService g = new GlossaryService(new DefaultResourceLoader(),
                    "file:" + tempDir.resolve("no-such-file.tsv"));
            assertEquals(0, g.size());
            assertEquals("AMF query", g.expand("AMF query"));
        }

        @Test
        void emptyFileYieldsAnEmptyGlossary() throws Exception {
            assertEquals(0, service("").size());
        }

        @Test
        void keysAndValuesAreTrimmed() throws Exception {
            GlossaryService g = service("  UPF  \t  User Plane Function  \n");
            assertEquals("UPF User Plane Function", g.expand("UPF"));
        }
    }
}

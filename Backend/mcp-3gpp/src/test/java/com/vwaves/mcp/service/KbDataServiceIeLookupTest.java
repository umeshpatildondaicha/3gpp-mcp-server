package com.vwaves.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link KbDataService#lookupIeDefinition} against real temp SQLite data:
 * the chunk-scan path (no clauses table) for all three ASN.1 shapes the
 * definition pattern accepts — top-level {@code ::= ENUMERATED}, inline
 * field without {@code ::=}, {@code INTEGER (a..b)} and {@code BIT STRING} —
 * plus release variants, series scoping, limits, and the clause-level index
 * path with its ie_name-first ordering and chunk fallback.
 */
class KbDataServiceIeLookupTest {

    @TempDir
    Path tmp;

    private static KbDataService newService() {
        return new KbDataService(KbSearchTestSupport.rerankOff(),
                KbSearchTestSupport.defaultProps(),
                KbSearchTestSupport.lexicon(),
                KbSearchTestSupport.embedding());
    }

    @Nested
    @DisplayName("chunk-scan path (no clause index)")
    class ChunkScanPath {

        private KbDataService kb;

        @BeforeEach
        void setUp() throws Exception {
            Path db = tmp.resolve("ie-chunks.db");
            try (Connection conn = KbSearchTestSupport.open(db)) {
                KbSearchTestSupport.createSchema(conn);
                KbSearchTestSupport.insertChunk(conn, "i1", "38.331", "Rel-18", "38",
                        "NR; RRC", "TS", "NR RRC", 0, 4,
                        "Timers and constants used by the rlc entity. "
                                + "t-PollRetransmit ::= ENUMERATED {ms5, ms10, ms15, ms20, ms25} "
                                + "controls the poll retransmission timer in acknowledged mode.");
                KbSearchTestSupport.insertChunk(conn, "i2", "38.331", "Rel-14", "38",
                        "NR; RRC", "TS", "NR RRC", 1, 4,
                        "Earlier release variant follows. "
                                + "t-PollRetransmit ::= ENUMERATED {ms5, ms10} was the original set.");
                KbSearchTestSupport.insertChunk(conn, "i3", "38.331", "Rel-18", "38",
                        "NR; RRC", "TS", "NR RRC", 2, 4,
                        "The field maxRetxThreshold ENUMERATED {t1, t2, t3, t4, t8} controls "
                                + "the maximum number of retransmissions before failure.");
                KbSearchTestSupport.insertChunk(conn, "i4", "38.331", "Rel-18", "38",
                        "NR; RRC", "TS", "NR RRC", 3, 4,
                        "Power limits. p-Max ::= INTEGER (-30..33) limits the maximum "
                                + "transmit power the ue may use in the serving cell.");
                KbSearchTestSupport.insertChunk(conn, "i5", "36.331", "Rel-15", "36",
                        "LTE; RRC", "TS", "E-UTRA RRC", 0, 2,
                        "For eutra the values differ: "
                                + "t-PollRetransmit ::= ENUMERATED {ms100, ms200} applies instead.");
                KbSearchTestSupport.insertChunk(conn, "i6", "36.331", "Rel-15", "36",
                        "LTE; RRC", "TS", "E-UTRA RRC", 1, 2,
                        "Identity handling. cellIdentity ::= BIT STRING (SIZE (28)) "
                                + "identifies a cell within the plmn");
                KbSearchTestSupport.insertMeta(conn, "embed_model", "test-model");
            }
            kb = newService();
            kb.init(List.of(db), new StartupState());
        }

        @Test
        @DisplayName("top-level ::= ENUMERATED definitions are found across releases and specs")
        void findsTopLevelEnumeratedInAllVariants() throws Exception {
            List<KbDataService.IeDefinition> defs =
                    kb.lookupIeDefinition("t-PollRetransmit", null, 10);

            assertThat(defs).hasSize(3)
                            .allMatch(d -> d.ieName().equals("t-PollRetransmit"));
            assertThat(defs).extracting(KbDataService.IeDefinition::release)
                    .containsExactlyInAnyOrder("Rel-18", "Rel-14", "Rel-15");
            KbDataService.IeDefinition rel18 = defs.stream()
                    .filter(d -> d.release().equals("Rel-18")).findFirst().orElseThrow();
            assertThat(rel18.definition()).isEqualTo("ENUMERATED {ms5, ms10, ms15, ms20, ms25}");
            assertThat(rel18.specId()).isEqualTo("38.331");
            // Context window captures prose around the match.
            assertThat(rel18.context()).contains("Timers and constants");
        }

        @Test
        @DisplayName("series filter scopes the scan to one spec family")
        void seriesFilterScopesLookup() throws Exception {
            List<KbDataService.IeDefinition> defs =
                    kb.lookupIeDefinition("t-PollRetransmit", "36", 10);

            assertThat(defs).hasSize(1);
            assertThat(defs.get(0).specId()).isEqualTo("36.331");
            assertThat(defs.get(0).definition()).contains("ms100, ms200");
        }

        @Test
        @DisplayName("limit truncates the result list")
        void limitIsRespected() throws Exception {
            assertThat(kb.lookupIeDefinition("t-PollRetransmit", null, 1)).hasSize(1);
        }

        @Test
        @DisplayName("inline field form without ::= is matched")
        void findsInlineFieldWithoutAssignment() throws Exception {
            List<KbDataService.IeDefinition> defs =
                    kb.lookupIeDefinition("maxRetxThreshold", null, 5);

            assertThat(defs).hasSize(1);
            assertThat(defs.get(0).definition()).startsWith("ENUMERATED {t1, t2, t3, t4, t8}");
        }

        @Test
        @DisplayName("INTEGER range form is matched")
        void findsIntegerRangeDefinition() throws Exception {
            List<KbDataService.IeDefinition> defs = kb.lookupIeDefinition("p-Max", null, 5);

            assertThat(defs).hasSize(1);
            assertThat(defs.get(0).definition()).isEqualTo("INTEGER (-30..33)");
        }

        @Test
        @DisplayName("BIT STRING form is matched")
        void findsBitStringDefinition() throws Exception {
            List<KbDataService.IeDefinition> defs =
                    kb.lookupIeDefinition("cellIdentity", null, 5);

            assertThat(defs).hasSize(1);
            assertThat(defs.get(0).definition()).startsWith("BIT STRING (SIZE (28))");
        }

        @Test
        @DisplayName("lookup is case-insensitive on the IE name")
        void lookupIsCaseInsensitive() throws Exception {
            List<KbDataService.IeDefinition> defs =
                    kb.lookupIeDefinition("T-POLLRETRANSMIT", null, 10);

            assertThat(defs).isNotEmpty();
            // The matched name keeps the corpus spelling, not the query spelling.
            assertThat(defs.get(0).ieName()).isEqualTo("t-PollRetransmit");
        }

        @Test
        @DisplayName("blank, null and unknown names return nothing")
        void degenerateNamesReturnEmpty() throws Exception {
            assertThat(kb.lookupIeDefinition(null, null, 5)).isEmpty();
            assertThat(kb.lookupIeDefinition("  ", null, 5)).isEmpty();
            assertThat(kb.lookupIeDefinition("noSuchElement", null, 5)).isEmpty();
        }
    }

    @Nested
    @DisplayName("clause-index path")
    class ClauseIndexPath {

        private KbDataService kb;

        @BeforeEach
        void setUp() throws Exception {
            Path db = tmp.resolve("ie-clauses.db");
            try (Connection conn = KbSearchTestSupport.open(db)) {
                KbSearchTestSupport.createSchema(conn);
                KbSearchTestSupport.createClausesTable(conn);
                // Definition-carrying clause: ie_name matches, ranked first.
                KbSearchTestSupport.insertClause(conn, "38.331", "Rel-18", "38",
                        "6.3.2", "t-PollRetransmit",
                        "t-PollRetransmit ::= ENUMERATED {ms5, ms10, ms15}");
                // A referencing clause that merely mentions the IE.
                KbSearchTestSupport.insertClause(conn, "38.331", "Rel-18", "38",
                        "6.3.3", "pollByte",
                        "When pollByte fires the timer t-PollRetransmit ENUMERATED "
                                + "{ms20, ms25} value is consulted again by the receiver.");
                // Series-36 variant for the series filter.
                KbSearchTestSupport.insertClause(conn, "36.331", "Rel-15", "36",
                        "9.1.1", "t-PollRetransmit",
                        "t-PollRetransmit ::= ENUMERATED {ms300}");
                // p-Max exists only as a CHUNK: clause miss must fall back.
                KbSearchTestSupport.insertChunk(conn, "f1", "38.331", "Rel-18", "38",
                        "NR; RRC", "TS", "NR RRC", 0, 1,
                        "Power limits. p-Max ::= INTEGER (-30..33) limits the maximum "
                                + "transmit power in the serving cell.");
                KbSearchTestSupport.insertMeta(conn, "embed_model", "test-model");
            }
            kb = newService();
            kb.init(List.of(db), new StartupState());
        }

        @Test
        @DisplayName("clause whose ie_name IS the IE outranks a clause merely mentioning it")
        void definitionClauseRanksBeforeReference() throws Exception {
            List<KbDataService.IeDefinition> defs =
                    kb.lookupIeDefinition("t-PollRetransmit", "38", 5);

            assertThat(defs).hasSize(2);
            assertThat(defs.get(0).definition()).isEqualTo("ENUMERATED {ms5, ms10, ms15}");
            // Clause units return the whole unit as context.
            assertThat(defs.get(0).context())
                    .isEqualTo("t-PollRetransmit ::= ENUMERATED {ms5, ms10, ms15}");
            assertThat(defs.get(1).definition()).isEqualTo("ENUMERATED {ms20, ms25}");
        }

        @Test
        @DisplayName("series filter applies inside the clause index too")
        void clauseSeriesFilterScopes() throws Exception {
            List<KbDataService.IeDefinition> defs =
                    kb.lookupIeDefinition("t-PollRetransmit", "36", 5);

            assertThat(defs).hasSize(1);
            assertThat(defs.get(0).specId()).isEqualTo("36.331");
            assertThat(defs.get(0).definition()).isEqualTo("ENUMERATED {ms300}");
        }

        @Test
        @DisplayName("an IE absent from the clause index falls back to the chunk scan")
        void clauseMissFallsBackToChunks() throws Exception {
            List<KbDataService.IeDefinition> defs = kb.lookupIeDefinition("p-Max", null, 5);

            assertThat(defs).hasSize(1);
            assertThat(defs.get(0).definition()).isEqualTo("INTEGER (-30..33)");
            assertThat(defs.get(0).context()).contains("Power limits");
        }
    }
}

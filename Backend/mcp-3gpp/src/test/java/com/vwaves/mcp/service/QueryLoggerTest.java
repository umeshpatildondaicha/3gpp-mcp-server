package com.vwaves.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vwaves.mcp.model.SearchFilter;
import com.vwaves.mcp.model.SearchHit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * QueryLogger is the only record of what production queries looked like, so the
 * tests pin the shape of the emitted JSON line: what MUST be present (query,
 * ranking view, latency), what must be OMITTED (blank filters, anything past the
 * top-5), and that disabling it silences the channel completely. Events are
 * captured by attaching a ListAppender to the dedicated 'telecom_kb.queries'
 * logback logger — no Spring context, no files.
 */
class QueryLoggerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Logger queryLogger;
    private ListAppender<ILoggingEvent> appender;
    private Level originalLevel;

    @BeforeEach
    void attachAppender() {
        queryLogger = (Logger) LoggerFactory.getLogger("telecom_kb.queries");
        originalLevel = queryLogger.getLevel();
        queryLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        queryLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        queryLogger.detachAppender(appender);
        queryLogger.setLevel(originalLevel);
    }

    private static SearchHit hit(String specId, double score) {
        return new SearchHit(score, specId, "Rel-18", "title", "series", "snippet");
    }

    /** The JSON payload of the single logged line, with the 'QLOG ' prefix verified. */
    private JsonNode singleLoggedJson() throws Exception {
        assertEquals(1, appender.list.size(), "expected exactly one QLOG line");
        String msg = appender.list.get(0).getFormattedMessage();
        assertTrue(msg.startsWith("QLOG "), "line must carry the greppable prefix: " + msg);
        return MAPPER.readTree(msg.substring("QLOG ".length()));
    }

    @Nested
    @DisplayName("logged JSON shape")
    class JsonShape {

        @Test
        void logsCoreFieldsForATypicalQuery() throws Exception {
            new QueryLogger(true).logQuery(
                    "pdu session establishment", 5, new SearchFilter("23", "Rel-18", "TS"),
                    0.5, 42L, List.of(hit("23.502", 0.91), hit("23.501", 0.85)));

            JsonNode json = singleLoggedJson();
            assertEquals("pdu session establishment", json.get("query").asText());
            assertEquals(5, json.get("topK").asInt());
            assertEquals("23", json.get("series").asText());
            assertEquals("Rel-18", json.get("release").asText());
            assertEquals("TS", json.get("docType").asText());
            assertEquals(42L, json.get("latency_ms").asLong());
            assertEquals(2, json.get("result_count").asInt());
            assertTrue(json.has("ts"), "must carry a timestamp");

            JsonNode top = json.get("top");
            assertEquals(2, top.size());
            assertEquals("23.502", top.get(0).get("spec_id").asText());
            assertEquals(0.91, top.get(0).get("score").asDouble(), 1e-9);
            assertEquals("23.501", top.get(1).get("spec_id").asText());
        }

        @Test
        void blankAndNullFiltersAreOmittedNotLoggedAsEmpty() throws Exception {
            new QueryLogger(true).logQuery(
                    "q", 3, new SearchFilter(null, "", "   "), 1.0, 1L, List.of());

            JsonNode json = singleLoggedJson();
            assertFalse(json.has("series"), "null filter must be omitted");
            assertFalse(json.has("release"), "empty filter must be omitted");
            assertFalse(json.has("docType"), "blank filter must be omitted");
        }

        @Test
        void topViewIsCappedAtFiveHits() throws Exception {
            List<SearchHit> seven = List.of(
                    hit("23.501", 0.9), hit("23.502", 0.8), hit("23.503", 0.7),
                    hit("29.500", 0.6), hit("29.510", 0.5), hit("38.331", 0.4),
                    hit("36.331", 0.3));

            new QueryLogger(true).logQuery("q", 10, SearchFilter.NONE, 1.0, 5L, seven);

            JsonNode json = singleLoggedJson();
            assertEquals(7, json.get("result_count").asInt(),
                    "result_count reflects ALL hits");
            assertEquals(5, json.get("top").size(),
                    "but the ranking view stops at 5");
        }

        @Test
        void emptyResultIsStillLogged() throws Exception {
            // Zero-hit queries are exactly the ones worth auditing.
            new QueryLogger(true).logQuery("unanswerable", 5, SearchFilter.NONE,
                    1.0, 7L, List.of());

            JsonNode json = singleLoggedJson();
            assertEquals(0, json.get("result_count").asInt());
            assertEquals(0, json.get("top").size());
        }

        @Test
        void extrasDbWeightIsRoundedToFourDecimals() throws Exception {
            new QueryLogger(true).logQuery("q", 5, SearchFilter.NONE,
                    0.123456789, 1L, List.of());

            assertEquals(0.1235, singleLoggedJson().get("extras_db_weight").asDouble(), 1e-9);
        }
    }

    @Nested
    @DisplayName("enable/disable switch")
    class EnableSwitch {

        @Test
        void disabledLoggerEmitsNothing() {
            new QueryLogger(false).logQuery("q", 5, new SearchFilter("23", "Rel-18", "TS"),
                    0.5, 42L, List.of(hit("23.501", 0.9)));
            assertTrue(appender.list.isEmpty(), "disabled logger must be silent");
        }

        @Test
        void eachEnabledCallEmitsExactlyOneLine() {
            QueryLogger logger = new QueryLogger(true);
            logger.logQuery("first", 5, SearchFilter.NONE, 1.0, 1L, List.of());
            logger.logQuery("second", 5, SearchFilter.NONE, 1.0, 1L, List.of());
            assertEquals(2, appender.list.size());
        }
    }
}

package com.vwaves.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vwaves.mcp.model.SearchFilter;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Covers the half of the {@link QueryLogger} entry guard that
 * {@code QueryLoggerTest} leaves out: {@code enabled=true} but the
 * 'telecom_kb.queries' logger level set above INFO. The logger must then skip
 * ALL serialisation work, not merely emit at a filtered level.
 */
class QueryLoggerLevelGateTest {

    private Logger queryLogger;
    private ListAppender<ILoggingEvent> appender;
    private Level originalLevel;

    @BeforeEach
    void attachAppender() {
        queryLogger = (Logger) LoggerFactory.getLogger("telecom_kb.queries");
        originalLevel = queryLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        queryLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        queryLogger.detachAppender(appender);
        queryLogger.setLevel(originalLevel);
    }

    @Test
    void enabledButInfoDisabledAtTheLoggerEmitsNothing() {
        queryLogger.setLevel(Level.WARN);   // INFO now disabled on this channel

        new QueryLogger(true).logQuery("pdu session establishment", 5,
                new SearchFilter("23", "Rel-18", "TS"), 0.5, 42L, List.of());

        assertTrue(appender.list.isEmpty(),
                "with INFO disabled the logger must short-circuit before serialising");
    }

    @Test
    void restoringInfoReenablesTheChannel() {
        queryLogger.setLevel(Level.WARN);
        QueryLogger logger = new QueryLogger(true);
        logger.logQuery("first", 5, SearchFilter.NONE, 1.0, 1L, List.of());

        queryLogger.setLevel(Level.INFO);
        logger.logQuery("second", 5, SearchFilter.NONE, 1.0, 1L, List.of());

        assertEquals(1, appender.list.size(),
                "only the call made while INFO was enabled may emit");
        assertTrue(appender.list.get(0).getFormattedMessage().contains("second"));
    }
}

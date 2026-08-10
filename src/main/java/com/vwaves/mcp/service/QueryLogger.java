package com.vwaves.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vwaves.mcp.model.SearchHit;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Per-query observability for the search3gpp tool.
 *
 * Logs ONE structured JSON line per call to a dedicated logger
 * ('telecom_kb.queries'). Configure logback to route this logger to its
 * own file:
 *
 *   <logger name="telecom_kb.queries" level="INFO" additivity="false">
 *     <appender-ref ref="QUERY_LOG_FILE" />
 *   </logger>
 *
 * Without that config, lines go to the standard log; you can grep for
 * 'QLOG' to filter.
 *
 * What we log:
 *   - timestamp, latency
 *   - original query, resolved topK + filters
 *   - extras_db_weight (so we can audit the discount fix in production)
 *   - top-5 results (spec_id + score + rank)
 *   - result_count, was_empty
 *
 * What we DON'T log:
 *   - full chunk text (privacy + log volume)
 *   - embedding vectors (huge)
 *
 * Why this matters: without query logs you have no way to find which
 * production queries the system gets wrong. Sample-mode is fine if
 * volume is high; this implementation logs every query (typical NOC
 * usage is well under that threshold).
 */
@Service
public class QueryLogger {
    private static final Logger log = LoggerFactory.getLogger("telecom_kb.queries");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean enabled;

    public QueryLogger(@Value("${app.query-log-enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    public void logQuery(
            String query,
            int topK,
            String series,
            String release,
            String docType,
            double extrasDbWeight,
            long latencyMs,
            List<SearchHit> hits
    ) {
        if (!enabled || !log.isInfoEnabled()) return;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", Instant.now().toString());
        entry.put("query", query);
        entry.put("topK", topK);
        if (series  != null && !series.isBlank())  entry.put("series",  series);
        if (release != null && !release.isBlank()) entry.put("release", release);
        if (docType != null && !docType.isBlank()) entry.put("docType", docType);
        entry.put("extras_db_weight", round4(extrasDbWeight));
        entry.put("latency_ms", latencyMs);
        entry.put("result_count", hits.size());

        // Compact top-5 view: rank | spec_id | score
        List<Map<String, Object>> top = hits.stream()
                .limit(5)
                .map(h -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("spec_id", h.specId());
                    m.put("score", h.score());
                    return m;
                })
                .collect(Collectors.toList());
        entry.put("top", top);

        try {
            log.info("QLOG {}", MAPPER.writeValueAsString(entry));
        } catch (Exception e) {
            // Never let logging fail a query
            log.warn("QLOG-fail: {}", e.getMessage());
        }
    }

    private static double round4(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }
}

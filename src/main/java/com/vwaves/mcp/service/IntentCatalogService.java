package com.vwaves.mcp.service;

import com.vwaves.mcp.config.RetrievalProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * The intent catalogue, loaded from {@code retrieval/intents.tsv} at startup.
 *
 * <p>Intents used to be a compiled {@code enum} with three hard-coded term
 * {@code Set}s beside it, which meant adding an intent, retuning a keyword list
 * or reordering the match precedence all required a recompile — inconsistent
 * with every other lexicon in this service, all of which are runtime resources.
 *
 * <p>Everything that used to be in code is now data: the intent identifiers, the
 * hint text, the keyword terms, the evaluation order (<em>priority</em>), and
 * whether a keyword match outranks the embedding classifier (<em>sticky</em>).
 *
 * <p>The catalogue never throws. A missing or malformed file yields an empty
 * catalogue and a single {@code unknown} fallback intent, so intent routing can
 * degrade without taking search down with it.
 */
@Service
public class IntentCatalogService {
    private static final Logger log = LoggerFactory.getLogger(IntentCatalogService.class);

    /**
     * @param id       stable identifier, also referenced by intent-exemplars.tsv
     * @param priority ascending evaluation order for keyword matching; the highest
     *                 value is the default when nothing matches
     * @param sticky   a keyword match on this intent may not be overridden by the
     *                 embedding classifier
     * @param hint     text surfaced to the orchestrator as "Intent: <hint>"
     */
    public record Intent(String id, int priority, boolean sticky, String hint) {
        /** Display form: "clause_lookup" → "clause-lookup". */
        public String label() {
            return id.replace('_', '-');
        }
    }

    /** Used only when the catalogue could not be loaded at all. */
    private static final Intent UNKNOWN =
            new Intent("unknown", Integer.MAX_VALUE, false,
                    "unknown — the intent catalogue could not be loaded");

    /** Weak terms only classify in company — a single hit is not enough. */
    private static final int MIN_WEAK_HITS = 2;

    /** An "intent" row: kind, id, priority, sticky, hint. */
    private static final int INTENT_ROW_MIN_COLUMNS = 5;
    private static final int PRIORITY_COLUMN = 2;
    private static final int STICKY_COLUMN = 3;
    private static final int HINT_COLUMN = 4;

    /** A "term"/"weak" row: kind, intent id, term. */
    private static final int TERM_ROW_MIN_COLUMNS = 3;
    private static final int TERM_COLUMN = 2;

    private final Map<String, Intent> byId;
    private final List<Intent> byPriority;
    private final Map<String, List<String>> termsByIntent;
    private final Map<String, List<String>> weakByIntent;

    public IntentCatalogService(ResourceLoader resourceLoader, RetrievalProperties props) {
        Map<String, Intent> intents = new LinkedHashMap<>();
        Map<String, List<String>> terms = new LinkedHashMap<>();
        Map<String, List<String>> weak = new LinkedHashMap<>();
        load(resourceLoader, props.intentsPath(), intents, terms, weak);
        this.byId = Map.copyOf(intents);
        this.termsByIntent = Map.copyOf(terms);
        this.weakByIntent = Map.copyOf(weak);
        List<Intent> sorted = new ArrayList<>(intents.values());
        sorted.sort(Comparator.comparingInt(Intent::priority));
        this.byPriority = List.copyOf(sorted);
        log.info("intent catalogue: {} intent(s), {} strong term(s), {} weak term(s), default='{}'",
                byId.size(), terms.values().stream().mapToInt(List::size).sum(),
                weak.values().stream().mapToInt(List::size).sum(), defaultIntent().id());
    }

    /** All intents, in keyword-evaluation order. */
    public List<Intent> intents() {
        return byPriority;
    }

    public Intent byId(String id) {
        Intent i = byId.get(id == null ? "" : id.toLowerCase(Locale.ROOT));
        return i == null ? defaultIntent() : i;
    }

    public boolean isKnown(String id) {
        return id != null && byId.containsKey(id.toLowerCase(Locale.ROOT));
    }

    /** Highest priority value wins the default slot; UNKNOWN if the file is empty. */
    public Intent defaultIntent() {
        return byPriority.isEmpty() ? UNKNOWN : byPriority.get(byPriority.size() - 1);
    }

    /**
     * Keyword classification: first intent, in priority order, with a term present
     * in the query. Returns {@code null} when nothing matches — the caller decides
     * whether that means "default" or "let the embedding classifier answer".
     */
    public Intent keywordMatch(String query) {
        if (query == null || query.isBlank()) return null;
        String q = query.toLowerCase(Locale.ROOT);
        for (Intent intent : byPriority) {
            for (String term : termsByIntent.getOrDefault(intent.id(), List.of())) {
                if (q.contains(term)) return intent;
            }
            // Weak terms only classify in company: a brand name alone is not a
            // commercial question, but "nokia … cli command" is.
            int weakHits = 0;
            for (String term : weakByIntent.getOrDefault(intent.id(), List.of())) {
                if (q.contains(term)) weakHits++;
            }
            if (weakHits >= MIN_WEAK_HITS) return intent;
        }
        return null;
    }

    /** Keyword classification with the default applied when nothing matches. */
    public Intent classifyByKeyword(String query) {
        Intent match = keywordMatch(query);
        return match == null ? defaultIntent() : match;
    }

    private static void load(ResourceLoader rl, String path,
                             Map<String, Intent> intents, Map<String, List<String>> terms,
                             Map<String, List<String>> weak) {
        Resource resource = rl.getResource(path);
        if (!resource.exists()) {
            log.warn("intents file not found at {} — intent routing disabled", path);
            return;
        }
        try (InputStream in = resource.getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                parseLine(line, lineNo, intents, terms, weak);
            }
        } catch (IOException e) {
            log.warn("failed reading intents from {}: {} — intent routing disabled", path, e.getMessage());
            return;
        }
        warnAboutUndeclaredIntents(intents, terms);
    }

    private static void parseLine(String line, int lineNo,
                                  Map<String, Intent> intents, Map<String, List<String>> terms,
                                  Map<String, List<String>> weak) {
        String t = line.strip();
        if (t.isEmpty() || t.startsWith("#")) return;
        String[] p = t.split("\t");
        String kind = p[0].strip().toLowerCase(Locale.ROOT);
        if ("intent".equals(kind) && p.length >= INTENT_ROW_MIN_COLUMNS) {
            parseIntentRow(p, lineNo, intents);
        } else if (("term".equals(kind) || "weak".equals(kind)) && p.length >= TERM_ROW_MIN_COLUMNS) {
            parseTermRow(kind, p, terms, weak);
        } else {
            log.warn("intents.tsv line {}: unrecognised row '{}' — skipped", lineNo, t);
        }
    }

    private static void parseIntentRow(String[] p, int lineNo, Map<String, Intent> intents) {
        String id = p[1].strip().toLowerCase(Locale.ROOT);
        Integer priority = parsePriority(p[PRIORITY_COLUMN], lineNo);
        if (priority == null) return;
        boolean sticky = Boolean.parseBoolean(p[STICKY_COLUMN].strip());
        String hint = p[HINT_COLUMN].strip();
        intents.put(id, new Intent(id, priority, sticky, hint));
    }

    /** @return the parsed priority, or {@code null} (with a warning) when non-numeric. */
    private static Integer parsePriority(String raw, int lineNo) {
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            log.warn("intents.tsv line {}: non-numeric priority '{}' — skipped", lineNo, raw);
            return null;
        }
    }

    private static void parseTermRow(String kind, String[] p,
                                     Map<String, List<String>> terms, Map<String, List<String>> weak) {
        String id = p[1].strip().toLowerCase(Locale.ROOT);
        String term = p[TERM_COLUMN].strip().toLowerCase(Locale.ROOT);
        if (!term.isEmpty()) {
            ("weak".equals(kind) ? weak : terms)
                    .computeIfAbsent(id, k -> new ArrayList<>()).add(term);
        }
    }

    /** Term rows naming an intent that was never declared are dead config. */
    private static void warnAboutUndeclaredIntents(Map<String, Intent> intents,
                                                   Map<String, List<String>> terms) {
        for (Map.Entry<String, List<String>> entry : terms.entrySet()) {
            if (!intents.containsKey(entry.getKey())) {
                log.warn("intents.tsv: {} term(s) reference undeclared intent '{}' — they will never match",
                        entry.getValue().size(), entry.getKey());
            }
        }
    }
}

package com.vwaves.mcp.service;

import com.vwaves.mcp.config.RetrievalProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * Scope gate: decides, before retrieval runs, whether the question is about a
 * specification this index does not hold.
 *
 * <p>Why this exists as a knowledge lookup rather than a score threshold:
 * measured on this corpus (2026-07-27), questions whose owning spec is absent
 * scored <em>higher</em> than questions answered correctly — median top score
 * 0.905 vs 0.780 — because with no true owner in the pool, one loosely-related
 * chunk wins uncontested. The signal is inverted, so no confidence threshold can
 * catch it. Without this gate the server answers "UPF restart and PFCP
 * association recovery" with 23.527 at high confidence.
 *
 * <p>The marker table is loaded from a classpath TSV. A marker only fires when
 * its owning spec is genuinely missing from the index, so ingesting the spec
 * later disables the corresponding markers automatically.
 */
@Service
public class ScopeGateService {
    private static final Logger log = LoggerFactory.getLogger(ScopeGateService.class);

    /**
     * One marker → the spec that owns it.
     *
     * @param block true  — nothing useful is indexed, so short-circuit retrieval.
     *              false — the OWNING spec is missing but related indexed content
     *                      exists, so run the search and attach a caveat. Blocking
     *                      here would be worse than the problem: "VPN pseudowire
     *                      down leading to link down" has no PW spec in the index,
     *                      but RFC 2863 (120 chunks) does define how a lower
     *                      sub-layer going down propagates linkDown upward — half
     *                      the answer, and suppressing it helps nobody.
     */
    private record Ownership(String marker, String specId, String topic, boolean block) {}

    /**
     * What the gate decided: block outright, or answer with a caveat.
     *
     * @param markers the marker terms that actually matched. Callers use these to
     *        build a residual query — see {@link #residualQuery}.
     */
    public record Decision(boolean block, String message, List<String> markers) {}

    /** A residual shorter than this is punctuation and stop-words, not a query. */
    private static final int MIN_RESIDUAL_CHARS = 8;

    private final List<Ownership> ownerships;
    /** Markers whose owning spec is absent from the index — the only ones that fire. */
    private volatile List<Ownership> active = List.of();

    public ScopeGateService(ResourceLoader resourceLoader, RetrievalProperties props) {
        this.ownerships = load(resourceLoader, props.specOwnershipPath());
    }

    /**
     * Resolve which markers are live, given what is actually indexed. Called once
     * at startup after the KB is loaded; until then the gate is inert (fails open).
     */
    public void resolveAgainstIndex(Set<String> indexedSpecIds) {
        List<Ownership> live = new ArrayList<>();
        for (Ownership o : ownerships) {
            if (!indexedSpecIds.contains(o.specId())) live.add(o);
        }
        this.active = List.copyOf(live);
        int suppressed = ownerships.size() - live.size();
        log.info("scope gate: {} marker(s) active, {} suppressed because their owning spec IS indexed",
                live.size(), suppressed);
    }

    /**
     * @return an explanation when the query is out of scope, or {@code null} to
     *         let retrieval proceed. Never throws; an unloadable table means the
     *         gate simply never fires.
     */
    /** Back-compat: the blocking half only. */
    public String outOfScopeReason(String query) {
        Decision d = decide(query);
        return (d != null && d.block()) ? d.message() : null;
    }

    /**
     * The query with the out-of-corpus vocabulary stripped out, or null when that
     * leaves nothing to search for.
     *
     * <p>Why this is needed: on a partial-coverage topic the generic mechanism is
     * indexed under DIFFERENT words than the missing topic. "pseudowire down link
     * down" ranks TR 23.802 and 36.803 at the score floor and never surfaces RFC
     * 2863, because "pseudowire" dominates the query vector and nothing indexed
     * contains it. Drop the marker and "down link down" retrieves the linkDown /
     * ifOperStatus material that actually answers the generic half.
     *
     * <p>Nothing topic-specific is encoded here — the words removed are exactly
     * the markers the gate already matched, so this needs no new configuration.
     */
    public String residualQuery(String query) {
        Decision d = decide(query);
        if (d == null || d.block() || d.markers().isEmpty()) return null;
        String residual = query;
        for (String marker : d.markers()) {
            residual = residual.replaceAll("(?i)\\b" + Pattern.quote(marker) + "\\b", " ");
        }
        residual = residual.replaceAll("\\s+", " ").strip();
        // Below this there is no query left, only stop-words and punctuation.
        return residual.length() < MIN_RESIDUAL_CHARS ? null : residual;
    }

    /** Caveat text for a partial-coverage topic, or null. */
    public String coverageCaveat(String query) {
        Decision d = decide(query);
        return (d != null && !d.block()) ? d.message() : null;
    }

    public Decision decide(String query) {
        if (query == null || query.isBlank() || active.isEmpty()) return null;
        String q = query.toLowerCase(Locale.ROOT);

        // Group the hits by owning spec so a query naming several markers of the
        // same spec reports it once.
        Map<String, String> byspec = new LinkedHashMap<>();
        List<String> matched = new ArrayList<>();
        boolean block = false;
        for (Ownership o : active) {
            if (containsWord(q, o.marker())) {
                byspec.putIfAbsent(o.specId(), o.topic());
                matched.add(o.marker());
                block |= o.block();
            }
        }
        if (byspec.isEmpty()) return null;

        if (!block) {
            StringBuilder note = new StringBuilder("Partial coverage: ");
            note.append(String.join(", ", matched)).append(" is specified in ");
            note.append(String.join(", ", byspec.keySet().stream()
                    .map(ScopeGateService::displaySpec).toList()));
            note.append(", which is NOT indexed. The results below are related "
                    + "indexed material, not that spec — treat them as background and "
                    + "use WebSearch for the authoritative definition.");
            return new Decision(false, note.toString(), List.copyOf(matched));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Not answerable from this knowledge base.\n\n");
        sb.append("The query mentions ").append(String.join(", ", matched))
          .append(", which ");
        sb.append(byspec.size() == 1 ? "is specified in:" : "are specified in:").append('\n');
        // Only 3GPP ids take a "TS" prefix; an IETF RFC id already names itself.
        byspec.forEach((spec, topic) -> sb.append("  • ").append(displaySpec(spec))
                .append(" — ").append(topic).append("  (NOT indexed)\n"));
        sb.append("\nThat spec is not in the index, so any result returned here would be a\n");
        sb.append("near-miss from a different spec rather than the actual answer. Use WebSearch\n");
        sb.append("for this one, or call listSpecs to see what is indexed.\n\n");
        sb.append("Confidence: none (out-of-scope)\n");
        sb.append("Intent: out-of-scope — the owning specification is absent from the corpus");
        return new Decision(true, sb.toString(), List.copyOf(matched));
    }

    /** "RFC3985" -> "RFC 3985"; a 3GPP id gets the "TS" prefix it expects. */
    private static String displaySpec(String spec) {
        return spec.toUpperCase(Locale.ROOT).startsWith("RFC")
                ? spec.replaceFirst("(?i)^rfc-?", "RFC ")
                : "TS " + spec;
    }

    /** Word-boundary containment so "n4" does not match "n40", "cdr" not "cdrom". */
    static boolean containsWord(String haystack, String needle) {
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            boolean leftOk = (idx == 0) || !isWordChar(haystack.charAt(idx - 1));
            int end = idx + needle.length();
            boolean rightOk = (end == haystack.length()) || !isWordChar(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            idx = end;
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }

    private static List<Ownership> load(ResourceLoader rl, String path) {
        List<Ownership> out = new ArrayList<>();
        Resource resource = rl.getResource(path);
        if (!resource.exists()) {
            log.warn("spec-ownership file not found at {} — scope gate disabled", path);
            return List.copyOf(out);
        }
        try (InputStream in = resource.getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] parts = t.split("\t");
                if (parts.length < 2) continue;
                String marker = parts[0].strip().toLowerCase(Locale.ROOT);
                String spec   = parts[1].strip();
                String topic  = parts.length > 2 ? parts[2].strip() : spec;
                // 4th column: "note" = partial coverage, answer with a caveat.
                // Absent or anything else = block, which stays the safe default.
                boolean block = parts.length <= 3
                        || !"note".equalsIgnoreCase(parts[3].strip());
                if (!marker.isEmpty() && !spec.isEmpty()) {
                    out.add(new Ownership(marker, spec, topic, block));
                }
            }
        } catch (IOException e) {
            log.warn("failed reading spec-ownership from {}: {} — scope gate disabled", path, e.getMessage());
        }
        log.info("spec-ownership table loaded: {} marker(s) from {}", out.size(), path);
        return List.copyOf(out);
    }

    /** Visible for tests. */
    int markerCount() { return ownerships.size(); }

    int activeMarkerCount() { return active.size(); }
}

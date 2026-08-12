package com.vwaves.mcp.service;

import com.vwaves.mcp.config.RetrievalProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * Loads three lexicons from classpath resources at startup so retrieval
 * tuning does not require recompilation:
 *   - stop-words.txt          (BM25 stop-word filtering)
 *   - and-term-subst.tsv      (canonicalisation map for AND query path)
 *   - non-3gpp-intent-terms.txt (lifts extras-DB discount when query has these tokens)
 *
 * Each file ignores blank lines and lines starting with '#'. Every entry is
 * lower-cased on load. Missing files produce a warning and an empty lexicon —
 * retrieval still works, just without the corresponding pruning/lifting.
 */
@Service
public class LexiconService {
    private static final Logger log = LoggerFactory.getLogger(LexiconService.class);

    /** Lexicon TSV rows are exactly: key TAB value. */
    private static final int TSV_COLUMNS = 2;

    private final Set<String> stopWords;
    private final Map<String, String> andTermSubst;
    private final Set<String> non3gppIntentTerms;
    private final Set<String> testSpecPrefixes;

    public LexiconService(ResourceLoader resourceLoader, RetrievalProperties props) {
        this.stopWords          = loadTokens(resourceLoader, props.stopWordsPath(), "stop-words");
        this.andTermSubst       = loadTsv(resourceLoader, props.andTermSubstPath(), "and-term-subst");
        this.non3gppIntentTerms = loadTokens(resourceLoader, props.non3gppIntentTermsPath(), "non-3gpp-intent-terms");
        this.testSpecPrefixes   = loadTokens(resourceLoader, props.testSpecPrefixesPath(), "test-spec-prefixes");
        log.info("lexicons loaded: stopWords={}, andTermSubst={}, non3gppIntentTerms={}, testSpecPrefixes={}",
                stopWords.size(), andTermSubst.size(), non3gppIntentTerms.size(), testSpecPrefixes.size());
    }

    public Set<String> stopWords()         { return stopWords; }
    public Map<String, String> andTermSubst() { return andTermSubst; }
    public Set<String> non3gppIntentTerms() { return non3gppIntentTerms; }

    /** Conformance/test spec families — excluded from procedure-flow evidence. */
    public boolean isTestSpec(String specId) {
        if (specId == null) return false;
        int dash = specId.indexOf('-');
        String head = dash > 0 ? specId.substring(0, dash) : specId;
        return testSpecPrefixes.contains(head.toLowerCase())
                || testSpecPrefixes.contains(specId.toLowerCase());
    }

    private static Set<String> loadTokens(ResourceLoader rl, String path, String label) {
        Set<String> out = new HashSet<>();
        Resource resource = rl.getResource(path);
        if (!resource.exists()) {
            log.warn("{} file not found at {} — feature degraded", label, path);
            return out;
        }
        try (InputStream in = resource.getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                out.add(t.toLowerCase());
            }
        } catch (IOException e) {
            log.warn("failed reading {} from {}: {}", label, path, e.getMessage());
        }
        return out;
    }

    private static Map<String, String> loadTsv(ResourceLoader rl, String path, String label) {
        Map<String, String> out = new HashMap<>();
        Resource resource = rl.getResource(path);
        if (!resource.exists()) {
            log.warn("{} file not found at {} — feature degraded", label, path);
            return out;
        }
        try (InputStream in = resource.getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                putTsvEntry(out, line);
            }
        } catch (IOException e) {
            log.warn("failed reading {} from {}: {}", label, path, e.getMessage());
        }
        return out;
    }

    private static void putTsvEntry(Map<String, String> out, String line) {
        String t = line.strip();
        if (t.isEmpty() || t.startsWith("#")) return;
        String[] parts = t.split("\t", TSV_COLUMNS);
        if (parts.length != TSV_COLUMNS) return;
        String k = parts[0].strip().toLowerCase();
        String v = parts[1].strip().toLowerCase();
        if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
    }
}

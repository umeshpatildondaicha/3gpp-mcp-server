package com.vwaves.mcp.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * Loads a TSV file of 3GPP abbreviations and expands them inside user queries
 * before retrieval. The vocabulary is configurable via app.glossary-path.
 *
 * Why: short acronyms like "PUCCH" or "SUCI" are tokenized poorly by general-
 * purpose embedders and rarely co-occur with their long form in spec text, so
 * appending the expansion lifts both dense and BM25 recall on telecom queries.
 */
@Service
public class GlossaryService {
    private static final Logger log = LoggerFactory.getLogger(GlossaryService.class);

    private final Map<String, String> abbreviations;

    public GlossaryService(
            ResourceLoader resourceLoader,
            @Value("${app.glossary-path:classpath:3gpp-vocab.tsv}") String path
    ) {
        this.abbreviations = load(resourceLoader, path);
        log.info("glossary loaded: {} entries from {}", abbreviations.size(), path);
    }

    public String expand(String query) {
        if (query == null || query.isBlank() || abbreviations.isEmpty()) {
            return query == null ? "" : query;
        }
        Set<String> alreadyAdded = new LinkedHashSet<>();
        StringBuilder appended = new StringBuilder();
        for (String token : query.split("\\s+")) {
            String cleaned = token.replaceAll("[^A-Za-z0-9-]", "").toUpperCase();
            if (cleaned.length() < 2 || cleaned.length() > 12) {
                continue;
            }
            String exp = abbreviations.get(cleaned);
            if (exp != null && alreadyAdded.add(cleaned)) {
                appended.append(' ').append(exp);
            }
        }
        return appended.length() == 0 ? query : query + appended.toString();
    }

    public int size() {
        return abbreviations.size();
    }

    private static Map<String, String> load(ResourceLoader rl, String path) {
        Map<String, String> map = new HashMap<>();
        Resource resource = rl.getResource(path);
        if (!resource.exists()) {
            log.warn("glossary file not found at {} — query expansion disabled", path);
            return map;
        }
        try (InputStream in = resource.getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\t", 2);
                if (parts.length != 2) {
                    continue;
                }
                String key = parts[0].strip().toUpperCase();
                String value = parts[1].strip();
                if (!key.isEmpty() && !value.isEmpty()) {
                    map.put(key, value);
                }
            }
        } catch (IOException e) {
            log.warn("failed reading glossary {}: {}", path, e.getMessage());
        }
        return map;
    }
}

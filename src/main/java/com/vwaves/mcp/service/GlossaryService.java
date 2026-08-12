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

@Service
public class GlossaryService {
    private static final Logger log = LoggerFactory.getLogger(GlossaryService.class);

    /** Tokens shorter/longer than this are never abbreviations worth expanding. */
    private static final int MIN_ABBREVIATION_LENGTH = 2;
    private static final int MAX_ABBREVIATION_LENGTH = 12;
    /** Glossary TSV rows are exactly: abbreviation TAB expansion. */
    private static final int TSV_COLUMNS = 2;

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
            if (cleaned.length() < MIN_ABBREVIATION_LENGTH || cleaned.length() > MAX_ABBREVIATION_LENGTH) {
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
                addEntry(map, line);
            }
        } catch (IOException e) {
            log.warn("failed reading glossary {}: {}", path, e.getMessage());
        }
        return map;
    }

    private static void addEntry(Map<String, String> map, String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }
        String[] parts = trimmed.split("\t", TSV_COLUMNS);
        if (parts.length != TSV_COLUMNS) {
            return;
        }
        String key   = parts[0].strip().toUpperCase();
        String value = parts[1].strip();
        if (!key.isEmpty() && !value.isEmpty()) {
            map.put(key, value);
        }
    }
}

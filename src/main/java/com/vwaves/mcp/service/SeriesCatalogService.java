package com.vwaves.mcp.service;

import com.vwaves.mcp.config.RetrievalProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * 3GPP series number → description, loaded from a classpath TSV.
 *
 * <p>Was a {@code Map.ofEntries(...)} constant in ThreeGppToolService. Moved here
 * so the catalogue can be corrected or extended — 3GPP adds series, and the
 * descriptions are editorial — without a recompile.
 */
@Service
public class SeriesCatalogService {
    private static final Logger log = LoggerFactory.getLogger(SeriesCatalogService.class);

    private final Map<String, String> catalog;

    public SeriesCatalogService(ResourceLoader resourceLoader, RetrievalProperties props) {
        this.catalog = load(resourceLoader, props.seriesCatalogPath());
        log.info("series catalogue: {} entries", catalog.size());
    }

    /** Entries in ascending series order. */
    public Set<Map.Entry<String, String>> entries() {
        return catalog.entrySet();
    }

    public String describe(String series) {
        return catalog.getOrDefault(series, "");
    }

    private static Map<String, String> load(ResourceLoader rl, String path) {
        Map<String, String> raw = new java.util.TreeMap<>();
        Resource resource = rl.getResource(path);
        if (!resource.exists()) {
            log.warn("series catalogue not found at {} — listSeries will be empty", path);
            return Map.of();
        }
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] p = t.split("\t", 2);
                if (p.length != 2) continue;
                String k = p[0].strip();
                String v = p[1].strip();
                if (!k.isEmpty() && !v.isEmpty()) raw.put(k, v);
            }
        } catch (IOException e) {
            log.warn("failed reading series catalogue from {}: {}", path, e.getMessage());
        }
        return new LinkedHashMap<>(raw);
    }
}

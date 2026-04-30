package com.vwaves.mcp.service;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BootstrapService {
    private static final Logger log = LoggerFactory.getLogger(BootstrapService.class);

    private final DbResolver dbResolver;
    private final KbDataService kbDataService;
    private final EmbeddingService embeddingService;
    private final StartupState startupState;

    public BootstrapService(
            DbResolver dbResolver,
            KbDataService kbDataService,
            EmbeddingService embeddingService,
            StartupState startupState
    ) {
        this.dbResolver = dbResolver;
        this.kbDataService = kbDataService;
        this.embeddingService = embeddingService;
        this.startupState = startupState;
    }

    @PostConstruct
    public void initialize() {
        try {
            Path dbPath = dbResolver.resolveDb();
            kbDataService.init(dbPath, startupState);
            embeddingService.init(startupState);

            String indexed = kbDataService.embedModelName();
            String runtime = embeddingService.modelName();
            if (indexed != null && !indexed.equalsIgnoreCase(runtime)) {
                throw new IllegalStateException(
                        "Embedding model mismatch — index built with '" + indexed
                                + "' but runtime is '" + runtime
                                + "'. Set app.embed-model-name and the spring.ai.embedding.transformer.* "
                                + "URIs to match the index, or rebuild the index.");
            }
            log.info("embedding model match OK (index='{}', runtime='{}')", indexed, runtime);

            startupState.phase("ready");
            startupState.ready(true);
            log.info("HTTP/SSE MCP server ready.");
        } catch (Exception e) {
            startupState.phase("failed");
            startupState.ready(false);
            log.error("Startup failed", e);
            throw new IllegalStateException("Startup failed: " + e.getMessage(), e);
        }
    }
}

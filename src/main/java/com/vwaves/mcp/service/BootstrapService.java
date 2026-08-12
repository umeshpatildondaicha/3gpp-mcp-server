package com.vwaves.mcp.service;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BootstrapService {
    private static final Logger log = LoggerFactory.getLogger(BootstrapService.class);

    private final DbResolver dbResolver;
    private final KbDataService kbDataService;
    private final EmbeddingService embeddingService;
    private final RerankService rerankService;
    private final ScopeGateService scopeGateService;
    private final IntentClassifierService intentClassifier;
    private final StartupState startupState;

    public BootstrapService(
            DbResolver dbResolver,
            KbDataService kbDataService,
            EmbeddingService embeddingService,
            RerankService rerankService,
            ScopeGateService scopeGateService,
            IntentClassifierService intentClassifier,
            StartupState startupState
    ) {
        this.dbResolver = dbResolver;
        this.kbDataService = kbDataService;
        this.embeddingService = embeddingService;
        this.rerankService = rerankService;
        this.scopeGateService = scopeGateService;
        this.intentClassifier = intentClassifier;
        this.startupState = startupState;
    }

    @PostConstruct
    public void initialize() {
        try {
            List<Path> dbPaths = List.of(dbResolver.resolveDb(), dbResolver.resolveDb2());
            kbDataService.init(dbPaths, startupState);
            embeddingService.init(startupState);
            rerankService.init(startupState);
            // Must run after the KB is loaded: a scope-gate marker only fires when
            // its owning spec is genuinely missing from the index.
            scopeGateService.resolveAgainstIndex(kbDataService.allSpecIds());
            // Needs the embedding model, so must follow embeddingService.init().
            intentClassifier.init();

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

            int indexedDim = kbDataService.embedDimFromMeta();
            int runtimeDim = embeddingService.dim();
            if (indexedDim > 0 && indexedDim != runtimeDim) {
                throw new IllegalStateException(
                        "Embedding dimension mismatch — index built with dim=" + indexedDim
                                + " but runtime produces dim=" + runtimeDim
                                + ". Set app.embed-dim to match the index, or rebuild the index.");
            }
            if (indexedDim == 0) {
                log.warn("DB meta table has no 'embed_dim' key; trusting runtime dim={}. " +
                        "Add INSERT INTO meta(key, value) VALUES('embed_dim', '{}') in the ingestion pipeline.",
                        runtimeDim, runtimeDim);
            } else {
                log.info("embedding dim match OK (index={}, runtime={})", indexedDim, runtimeDim);
            }

            startupState.phase("ready");
            startupState.ready(true);
            log.info("HTTP/SSE MCP server ready.");
        } catch (Exception e) {
            startupState.phase("failed");
            startupState.ready(false);
            // Rethrown with context; the exception (and its cause) is logged by the
            // container's startup failure handling, so no duplicate log here (S2139).
            throw new IllegalStateException("Startup failed: " + e.getMessage(), e);
        }
    }
}

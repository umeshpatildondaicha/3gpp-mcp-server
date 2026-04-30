package com.vwaves.mcp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingModel embeddingModel;
    private final String modelName;
    private final int dim;

    public EmbeddingService(
            EmbeddingModel embeddingModel,
            @Value("${app.embed-model-name:all-MiniLM-L6-v2}") String modelName,
            @Value("${app.embed-dim:384}") int dim
    ) {
        this.embeddingModel = embeddingModel;
        this.modelName = modelName;
        this.dim = dim;
    }

    public void init(StartupState startupState) {
        startupState.phase("loading-model");
        long t0 = System.currentTimeMillis();
        float[] probe = embed("warmup");
        if (probe.length != dim) {
            throw new IllegalStateException(
                    "Configured embed-dim=" + dim + " but model produced " + probe.length);
        }
        log.info("embedding model '{}' ready (dim={}, warmup {} ms)",
                modelName, dim, System.currentTimeMillis() - t0);
    }

    public float[] embed(String text) {
        String input = text == null ? "" : text;
        if (input.isBlank()) {
            return new float[dim];
        }
        float[] v = embeddingModel.embed(input);
        l2Normalize(v);
        return v;
    }

    public String modelName() {
        return modelName;
    }

    public int dim() {
        return dim;
    }

    private static void l2Normalize(float[] v) {
        double sum = 0.0;
        for (float x : v) sum += x * x;
        double mag = Math.sqrt(sum);
        if (mag <= 0) return;
        float inv = (float) (1.0 / mag);
        for (int i = 0; i < v.length; i++) v[i] *= inv;
    }
}

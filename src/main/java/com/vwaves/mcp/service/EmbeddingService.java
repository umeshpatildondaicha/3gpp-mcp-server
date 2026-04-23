package com.vwaves.mcp.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingService {
    private static final int DIM = 384;

    public void init(StartupState startupState) {
        startupState.phase("loading-model");
    }

    public float[] embed(String text) {
        float[] vector = new float[DIM];
        String normalized = text == null ? "" : text.strip().toLowerCase();
        if (normalized.isEmpty()) {
            return vector;
        }
        String[] tokens = normalized.split("\\s+");
        for (String token : tokens) {
            int bucket = Math.floorMod(token.hashCode(), DIM);
            vector[bucket] += 1f;
            byte[] digest = sha256(token);
            for (int i = 0; i < digest.length; i += 4) {
                int idx = Math.floorMod((bucket + i), DIM);
                int raw = ((digest[i] & 0xFF) << 24)
                        | ((digest[i + 1] & 0xFF) << 16)
                        | ((digest[i + 2] & 0xFF) << 8)
                        | (digest[i + 3] & 0xFF);
                vector[idx] += (raw / (float) Integer.MAX_VALUE) * 0.05f;
            }
        }
        normalize(vector);
        return vector;
    }

    private byte[] sha256(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void normalize(float[] vector) {
        float sum = 0f;
        for (float v : vector) {
            sum += v * v;
        }
        float mag = (float) Math.sqrt(sum);
        if (mag == 0f) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= mag;
        }
    }
}

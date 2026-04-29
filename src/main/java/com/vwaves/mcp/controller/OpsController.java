package com.vwaves.mcp.controller;

import com.vwaves.mcp.service.KbDataService;
import com.vwaves.mcp.service.StartupState;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OpsController {
    private final StartupState startupState;
    private final KbDataService kbDataService;
    private final long startMillis = System.currentTimeMillis();

    public OpsController(StartupState startupState, KbDataService kbDataService) {
        this.startupState = startupState;
        this.kbDataService = kbDataService;
    }

    @GetMapping("/")
    public Map<String, Object> info() {
        return Map.of(
                "name", "3gpp-telecom-kb MCP Server",
                "version", "2.0.0",
                "mcp", "/mcp",
                "health", "/health",
                "ready", "/ready"
        );
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        long up = Duration.ofMillis(System.currentTimeMillis() - startMillis).toSeconds();
        return Map.of(
                "status", "ok",
                "ready", startupState.ready(),
                "startup_phase", startupState.phase(),
                "name", "3gpp-telecom-kb",
                "version", "2.0.0",
                "uptime_sec", up,
                "vectors", kbDataService.vectorCount()
        );
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        if (!startupState.ready()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("ready", false, "phase", startupState.phase()));
        }
        return ResponseEntity.ok(Map.of("ready", true));
    }

    // Liveness/readiness probe endpoint expected by VisionWaves k8s deployment
    @GetMapping("/rest/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        if (!startupState.ready()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "starting", "phase", startupState.phase()));
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}

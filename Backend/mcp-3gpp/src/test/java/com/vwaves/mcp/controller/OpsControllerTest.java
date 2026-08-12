package com.vwaves.mcp.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vwaves.mcp.service.KbDataService;
import com.vwaves.mcp.service.StartupState;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Plain unit tests for {@link OpsController} — constructed directly with a
 * real {@link StartupState} and a mocked {@link KbDataService}; no Spring
 * context, no MockMvc, no database.
 */
class OpsControllerTest {

    private StartupState startupState;
    private KbDataService kbDataService;
    private OpsController controller;

    @BeforeEach
    void setUp() {
        startupState = new StartupState();
        kbDataService = mock(KbDataService.class);
        when(kbDataService.vectorCount()).thenReturn(123_456);
        controller = new OpsController(startupState, kbDataService);
    }

    private static HttpServletRequest requestWithContextPath(String contextPath) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContextPath()).thenReturn(contextPath);
        return request;
    }

    // ── GET / ────────────────────────────────────────────────────────────────

    @Nested
    class Info {

        @Test
        void advertisesEndpointsRelativeToTheContextPath() {
            Map<String, Object> body = controller.info(requestWithContextPath("/api"));

            assertThat(body).containsEntry("name", "3gpp-telecom-kb MCP Server")
                            .containsEntry("version", "2.0.0")
                            .containsEntry("mcp", "/api/mcp")
                            .containsEntry("health", "/api/health")
                            .containsEntry("ready", "/api/ready")
                            .containsEntry("actuator_health", "/api/actuator/health");
        }

        @Test
        void rootContextPathYieldsAbsolutePaths() {
            // Servlet spec: the root context path is the empty string.
            Map<String, Object> body = controller.info(requestWithContextPath(""));
            assertThat(body).containsEntry("mcp", "/mcp")
                            .containsEntry("ready", "/ready");
        }
    }

    // ── GET /health ──────────────────────────────────────────────────────────

    @Nested
    class Health {

        @Test
        void reportsOkWithStartupPhaseAndVectorCountWhileStarting() {
            startupState.phase("loading-db");

            Map<String, Object> body = controller.health();

            // Liveness stays "ok" even before readiness — k8s must not kill a
            // pod that is still loading the KB.
            assertThat(body).containsEntry("status", "ok")
                            .containsEntry("ready", false)
                            .containsEntry("startup_phase", "loading-db")
                            .containsEntry("name", "3gpp-telecom-kb")
                            .containsEntry("version", "2.0.0")
                            .containsEntry("vectors", 123_456);
            assertThat((Long) body.get("uptime_sec")).isGreaterThanOrEqualTo(0L);
        }

        @Test
        void reflectsReadinessOnceStartupCompletes() {
            startupState.ready(true);
            assertThat(controller.health()).containsEntry("ready", true);
        }
    }

    // ── GET /ready ───────────────────────────────────────────────────────────

    @Nested
    class Ready {

        @Test
        void returns503WithPhaseWhileNotReady() {
            startupState.phase("building-fts");

            ResponseEntity<Map<String, Object>> resp = controller.ready();

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(resp.getBody()).containsEntry("ready", false)
                                      .containsEntry("phase", "building-fts");
        }

        @Test
        void returns200OnceReady() {
            startupState.ready(true);

            ResponseEntity<Map<String, Object>> resp = controller.ready();

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).containsEntry("ready", true);
            assertThat(resp.getBody()).doesNotContainKey("phase");
        }
    }

    // ── GET /rest/ping ───────────────────────────────────────────────────────

    @Nested
    class Ping {

        @Test
        void returns503StartingWithPhaseWhileNotReady() {
            startupState.phase("loading-reranker");

            ResponseEntity<Map<String, Object>> resp = controller.ping();

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(resp.getBody()).containsEntry("status", "starting")
                                      .containsEntry("phase", "loading-reranker");
        }

        @Test
        void returns200OkOnceReady() {
            startupState.ready(true);

            ResponseEntity<Map<String, Object>> resp = controller.ping();

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).containsEntry("status", "ok");
        }

        @Test
        void flipsBackTo503IfReadinessIsWithdrawn() {
            startupState.ready(true);
            assertThat(controller.ping().getStatusCode()).isEqualTo(HttpStatus.OK);

            startupState.ready(false);
            assertThat(controller.ping().getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}

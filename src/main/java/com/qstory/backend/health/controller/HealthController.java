package com.qstory.backend.health.controller;

import com.qstory.backend.config.AppProperties;
import com.qstory.backend.provider.ProviderReadiness;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Health", description = "Liveness/readiness probe for load balancers and Docker HEALTHCHECK")
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final AppProperties config;
    private final JdbcTemplate jdbcTemplate;

    public HealthController(AppProperties config, JdbcTemplate jdbcTemplate) {
        this.config = config;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Operation(
            summary = "Service health and provider readiness",
            description = "Always returns 200 while the process is up - never fails. `providers` reports "
                    + "whether each external provider (STT/LLM/TTS/image) has its API key/credentials configured, "
                    + "not whether that provider is currently reachable.")
    @ApiResponse(responseCode = "200", description = "Service is up",
            content = @Content(schema = @Schema(example = "{\"ok\":true,\"service\":\"q-story-speech-api\","
                    + "\"release\":\"spring-boot-1\",\"providers\":{\"stt\":true,\"llm\":true,\"tts\":true,\"image\":true}}")))
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("service", "q-story-speech-api");
        body.put("release", "spring-boot-1");
        body.put("providers", ProviderReadiness.of(config));
        return body;
    }

    /**
     * 바깥에서 1분마다 두드리는 준비 상태 확인(Grafana Synthetic Monitoring). /health는 프로세스가 떠 있으면
     * 항상 200이라 Railway 배포 확인에 쓰고, 이건 DB까지 붙는지 본다 - DB가 안 되면 503.
     */
    @Operation(
            summary = "Readiness - process up and database reachable",
            description = "200 when a trivial query succeeds, 503 otherwise. Used by the external uptime check, "
                    + "not by the Railway deploy healthcheck (that stays on /health).")
    @ApiResponse(responseCode = "200", description = "Ready",
            content = @Content(schema = @Schema(example = "{\"ok\":true,\"db\":\"up\"}")))
    @ApiResponse(responseCode = "503", description = "Database unreachable",
            content = @Content(schema = @Schema(example = "{\"ok\":false,\"db\":\"down\"}")))
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        try {
            jdbcTemplate.queryForObject("select 1", Integer.class);
            return ResponseEntity.ok(Map.of("ok", true, "db", "up"));
        } catch (RuntimeException failure) {
            log.error("health.db-failed reason={}", failure.toString());
            return ResponseEntity.status(503).body(Map.of("ok", false, "db", "down"));
        }
    }
}

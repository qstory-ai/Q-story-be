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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mirrors server.mjs's GET /health. */
@Tag(name = "Health", description = "Liveness/readiness probe for load balancers and Docker HEALTHCHECK")
@RestController
public class HealthController {

    private final AppProperties config;

    public HealthController(AppProperties config) {
        this.config = config;
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
}

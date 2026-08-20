package com.qstory.backend.health;

import com.qstory.backend.config.AppProperties;
import com.qstory.backend.provider.ProviderReadiness;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mirrors server.mjs's GET /health. */
@RestController
public class HealthController {

    private final AppProperties config;

    public HealthController(AppProperties config) {
        this.config = config;
    }

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

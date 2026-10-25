package com.qstory.backend.reportanalysis.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.reportanalysis.service.DemoReportAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Demo report analysis", description = "Interest analysis and talk cards for an anonymous demo play session")
@RestController
public class DemoReportAnalysisController {

    private final DemoReportAnalysisService service;

    public DemoReportAnalysisController(DemoReportAnalysisService service) {
        this.service = service;
    }

    @Operation(summary = "Start the report analysis for an anonymous demo session",
            description = "No login. Body: {betaSessionId, readFromSceneId?, readThroughSceneId?}. The session must have no owner "
                    + "and the betaSessionId must match (else 404). Idempotent - returns the current analysis "
                    + "({status: PENDING|READY|FAILED|SKIPPED, observations, cards, commonScenes}).")
    @PostMapping("/v1/play-sessions/{sessionId}/demo-analysis")
    public Map<String, Object> start(@PathVariable UUID sessionId, @RequestBody JsonNode body) {
        return service.start(sessionId, betaSessionId(body.path("betaSessionId").asText(null)),
                body.path("readFromSceneId").asText(null), body.path("readThroughSceneId").asText(null));
    }

    @Operation(summary = "Read the report analysis for an anonymous demo session")
    @GetMapping("/v1/play-sessions/{sessionId}/demo-analysis")
    public Map<String, Object> find(@PathVariable UUID sessionId, @RequestParam(required = false) String betaSessionId) {
        return service.find(sessionId, betaSessionId(betaSessionId));
    }

    private static UUID betaSessionId(String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim());
        } catch (IllegalArgumentException error) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "체험 세션 정보가 없어요.");
        }
    }
}

package com.qstory.backend.interaction.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.error.FailureBody;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.interaction.service.UsageTrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 마이페이지 "화면 이용 기록" 스위치(073). 끄면 이 계정으로 오는 상호작용을 저장하지 않는다. */
@Tag(name = "Interactions", description = "Batched screen interaction events (view/leave/tap/scroll/hesitation) for UT analysis")
@RestController
@RequestMapping("/v1/me/usage-tracking")
public class UsageTrackingController {

    private final UsageTrackingService service;
    private final CurrentUserResolver currentUserResolver;

    public UsageTrackingController(UsageTrackingService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Read whether interaction tracking is on for the caller", description = "Any signed-in role. {enabled}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @GetMapping
    public Map<String, Object> read() {
        return Map.of("enabled", service.isEnabled(currentUserResolver.require().userId()));
    }

    @Operation(summary = "Turn interaction tracking on or off for the caller",
            description = "Any signed-in role. Body {enabled: boolean}. While off, POST /v1/interactions from this "
                    + "account answers 202 {ok:true, accepted:0} without storing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "400", description = "enabled is not a boolean",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @PostMapping
    public Map<String, Object> update(@RequestBody(required = false) JsonNode body) {
        UUID userId = currentUserResolver.require().userId();
        if (body == null || !body.path("enabled").isBoolean()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "enabled는 true 또는 false여야 해요.");
        }
        return Map.of("enabled", service.setEnabled(userId, body.path("enabled").asBoolean()));
    }
}

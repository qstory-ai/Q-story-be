package com.qstory.backend.recordingconsent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.common.error.FailureBody;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.recordingconsent.service.RecordingConsentService;
import com.qstory.backend.recordingconsent.service.RecordingConsentService.Decision;
import com.qstory.backend.recordingconsent.service.RecordingConsentService.Status;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화면 녹화 동의(073). 베타 세션 단위 동의는 로그인 없이 받고, 계정 단위 동의는 로그인한 본인만 읽고 바꾼다.
 * 철회(granted=false)는 이미 받은 녹화를 지운다.
 */
@Tag(name = "Recording consent", description = "Opt-in consent for rrweb screen recording (per beta session or per account)")
@RestController
public class RecordingConsentController {

    private final RecordingConsentService service;
    private final CurrentUserResolver currentUserResolver;

    public RecordingConsentController(RecordingConsentService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Record a per-beta-session screen recording decision",
            description = "Anonymous allowed. Body {betaSessionId, granted, source} - source is PROMPT, UT_LINK or LESSON. "
                    + "Replaces the previous decision for this beta session; granted=false also deletes the recording "
                    + "chunks already stored for it. Responds 202 {ok:true}.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Recorded"),
            @ApiResponse(responseCode = "400", description = "Missing betaSessionId, non-boolean granted or unknown source",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @PostMapping("/v1/recording-consents")
    public ResponseEntity<Map<String, Object>> decideForBetaSession(@RequestBody(required = false) JsonNode body) {
        Decision decision = RecordingConsentService.parse(body, RecordingConsentService.SESSION_SOURCES, true);
        service.decideForBetaSession(decision.betaSessionId(), decision.granted(), decision.source());
        return ResponseEntity.status(202).body(Map.of("ok", true));
    }

    @Operation(summary = "Read the caller's account-level screen recording consent",
            description = "Any signed-in role. {granted, source, decidedAt} - all null when the caller never decided.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @GetMapping("/v1/me/recording-consent")
    public Status readForAccount() {
        return service.accountStatus(currentUserResolver.require().userId());
    }

    @Operation(summary = "Record the caller's account-level screen recording decision",
            description = "Any signed-in role. Body {granted, source} - source is ACCOUNT, SIGNUP or PROMPT. granted=false "
                    + "deletes every recording chunk linked to this account (uploaded with it, or of beta sessions linked "
                    + "to it) and turns those beta sessions' consent off. Responds with the same shape as GET.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recorded"),
            @ApiResponse(responseCode = "400", description = "Non-boolean granted or unknown source",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @PostMapping("/v1/me/recording-consent")
    public Status decideForAccount(@RequestBody(required = false) JsonNode body) {
        UUID userId = currentUserResolver.require().userId();
        Decision decision = RecordingConsentService.parse(body, RecordingConsentService.ACCOUNT_SOURCES, false);
        return service.decideForAccount(userId, decision.granted(), decision.source());
    }
}

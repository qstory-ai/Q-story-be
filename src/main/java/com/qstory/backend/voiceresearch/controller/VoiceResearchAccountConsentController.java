package com.qstory.backend.voiceresearch.controller;

import com.qstory.backend.common.error.FailureBody;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.voiceresearch.dto.GrantVoiceResearchConsentRequest;
import com.qstory.backend.voiceresearch.dto.VoiceResearchConsentStatusResponse;
import com.qstory.backend.voiceresearch.service.VoiceResearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 마이페이지 "개인정보 및 데이터"의 음성 연구 동의 - 로그인한 보호자 본인의 계정 단위 동의만 다룬다. */
@Tag(name = "Voice research", description = "Opt-in recording upload/withdrawal for the parent-consented voice research program - separate from the child-facing question pipeline")
@RestController
@RequestMapping("/v1/me/voice-research-consent")
public class VoiceResearchAccountConsentController {

    private final VoiceResearchService service;
    private final CurrentUserResolver currentUserResolver;

    public VoiceResearchAccountConsentController(VoiceResearchService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Read the caller's account-level voice research consent",
            description = "PARENT only. explicit=false means the parent never changed it and the server default applies.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "403", description = "Not a PARENT",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @GetMapping
    public VoiceResearchConsentStatusResponse read() {
        return service.accountStatus(currentUserResolver.requireRole(Role.PARENT));
    }

    @Operation(summary = "Grant account-level voice research consent",
            description = "PARENT only. Body {consentVersion} must equal the current consent version "
                    + "(voice-research-v2-shadow-family); a stale version is rejected with 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Granted"),
            @ApiResponse(responseCode = "409", description = "consentVersion is not the current version",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @PostMapping
    public VoiceResearchConsentStatusResponse grant(@RequestBody GrantVoiceResearchConsentRequest request) {
        return service.grantForAccount(
                currentUserResolver.requireRole(Role.PARENT), request == null ? null : request.consentVersion());
    }

    @Operation(summary = "Withdraw account-level voice research consent",
            description = "PARENT only. Turns consent off (later uploads from this account get 403) and deletes "
                    + "every recording linked to this account, including the Supabase Storage objects.")
    @PostMapping("/withdraw")
    public VoiceResearchConsentStatusResponse withdraw() {
        return service.withdrawForAccount(currentUserResolver.requireRole(Role.PARENT));
    }
}

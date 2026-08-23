package com.qstory.backend.voiceresearch.controller;
import com.qstory.backend.voiceresearch.service.VoiceResearchService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.enums.CoverageStatus;
import com.qstory.backend.common.error.EdgeErrorCode;
import com.qstory.backend.common.error.EdgeException;
import com.qstory.backend.common.error.FailureBody;
import com.qstory.backend.common.util.HttpJsonWriter;
import com.qstory.backend.voiceresearch.dto.UploadRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Java port of supabase/functions/voice-research/index.ts's upload/withdraw actions. */
@Tag(name = "Voice research", description = "Opt-in recording upload/withdrawal for the parent-consented voice research program - separate from the child-facing question pipeline")
@RestController
public class VoiceResearchController {

    private final ObjectMapper objectMapper;
    private final VoiceResearchService service;

    public VoiceResearchController(ObjectMapper objectMapper, VoiceResearchService service) {
        this.objectMapper = objectMapper;
        this.service = service;
    }

    @Operation(
            summary = "Upload one opted-in question recording",
            description = "multipart/form-data - the audio part plus one form field per UploadRequest column "
                    + "(consent_id, deletion_token, consented_at, sample_id, story_id, scene_id, anchor_id, "
                    + "stt_draft, confirmed_transcript, question_round, duration_millis, and the optional "
                    + "coverage_status/family_id/intent_summary once the question has been routed). "
                    + "consent_id must reference a non-expired VoiceResearchConsent.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted",
                    content = @Content(schema = @Schema(example = "{\"ok\":true}"))),
            @ApiResponse(responseCode = "400", description = "Missing/malformed field, or an unsupported coverage_status",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "403", description = "consent_id unknown, expired, or deletion_token mismatch",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @PostMapping(value = "/v1/voice-research", consumes = "multipart/form-data")
    public void upload(
            @Parameter(description = "VoiceResearchConsent.id") @RequestParam("consent_id") String consentId,
            @Parameter(description = "Caller-generated, checked against consent_id's stored hash") @RequestParam("deletion_token") String deletionToken,
            @Parameter(description = "ISO-8601 instant, must be after the consent's consentedAt and before its expiresAt") @RequestParam("consented_at") String consentedAt,
            @Parameter(description = "Caller-generated UUID for this sample") @RequestParam("sample_id") String sampleId,
            @RequestParam("story_id") String storyId,
            @RequestParam("scene_id") String sceneId,
            @RequestParam("anchor_id") String anchorId,
            @Parameter(description = "STT provider's raw draft transcript, max 240 chars") @RequestParam("stt_draft") String sttDraft,
            @Parameter(description = "Parent/child-confirmed final transcript, max 240 chars") @RequestParam("confirmed_transcript") String confirmedTranscript,
            @RequestParam("question_round") int questionRound,
            @RequestParam("duration_millis") int durationMillis,
            @Parameter(description = "Set once routed: \"exact\" | \"partial\" | \"uncovered\"") @RequestParam(value = "coverage_status", required = false) String coverageStatus,
            @Parameter(description = "Set once routed: the action-family id the question was routed to") @RequestParam(value = "family_id", required = false) String familyId,
            @Parameter(description = "Set once routed, max 240 chars") @RequestParam(value = "intent_summary", required = false) String intentSummary,
            @Parameter(description = "audio/mp4, audio/m4a, audio/mpeg, audio/wav, audio/x-wav, audio/flac, or audio/webm")
            @RequestPart("audio") MultipartFile audio,
            HttpServletResponse response) throws IOException {
        UploadRequest request = new UploadRequest(
                parseUuid(consentId), deletionToken, parseInstant(consentedAt), parseUuid(sampleId), storyId,
                sceneId, anchorId, sttDraft, confirmedTranscript, questionRound, durationMillis, audio,
                parseCoverageStatus(coverageStatus), familyId, intentSummary);
        service.upload(request);
        HttpJsonWriter.writeJson(response, objectMapper, 202, Map.of("ok", true));
    }

    /**
     * The FE (voice-research.ts) sends both upload (multipart) and withdraw (JSON, {@code {action:'withdraw',...}})
     * requests to a single base URL, mirroring the original Supabase edge function's action-based dispatch
     * (supabase/functions/voice-research/index.ts). Keep accepting that shape here so a client pointed at this
     * base URL for both operations keeps working, in addition to the dedicated /withdraw route below.
     */
    @Operation(
            summary = "Legacy action-dispatch alias for withdraw",
            description = "JSON body {action:\"withdraw\", consent_id, deletion_token} posted to the same base "
                    + "URL as the multipart upload route - only action=\"withdraw\" is supported. Prefer POST "
                    + "/v1/voice-research/withdraw directly for new callers.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Withdrawn",
                    content = @Content(schema = @Schema(example = "{\"ok\":true}"))),
            @ApiResponse(responseCode = "400", description = "action is not \"withdraw\", or a required field is missing",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "403", description = "consent_id unknown or deletion_token mismatch",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @PostMapping(value = "/v1/voice-research", consumes = "application/json")
    public void dispatchJson(@RequestBody JsonNode body, HttpServletResponse response) throws IOException {
        String action = body == null ? "" : body.path("action").asText("");
        if (!"withdraw".equals(action)) {
            throw new EdgeException(EdgeErrorCode.UNSUPPORTED_ACTION);
        }
        withdraw(body, response);
    }

    @Operation(
            summary = "Withdraw consent and delete all of its recordings",
            description = "JSON body {consent_id, deletion_token} - deletion_token must match the token given "
                    + "at upload time. Deletes the consent row (cascading to its samples) and the underlying "
                    + "Supabase Storage objects.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Withdrawn",
                    content = @Content(schema = @Schema(example = "{\"ok\":true}"))),
            @ApiResponse(responseCode = "400", description = "Missing consent_id or deletion_token",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "403", description = "consent_id unknown or deletion_token mismatch",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "{consent_id, deletion_token}", required = true)
    @PostMapping("/v1/voice-research/withdraw")
    public void withdraw(@RequestBody JsonNode body, HttpServletResponse response) throws IOException {
        if (body == null || !body.hasNonNull("consent_id") || !body.hasNonNull("deletion_token")) {
            throw new EdgeException(EdgeErrorCode.VALIDATION_FAILED);
        }
        service.withdraw(parseUuid(body.get("consent_id").asText()), body.get("deletion_token").asText());
        HttpJsonWriter.writeJson(response, objectMapper, 200, Map.of("ok", true));
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception malformed) {
            throw new EdgeException(EdgeErrorCode.VALIDATION_FAILED);
        }
    }

    private Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception malformed) {
            throw new EdgeException(EdgeErrorCode.INVALID_CONSENT_TIME);
        }
    }

    private CoverageStatus parseCoverageStatus(String value) {
        if (value == null) {
            return null;
        }
        try {
            return CoverageStatus.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new EdgeException(EdgeErrorCode.VALIDATION_FAILED);
        }
    }
}

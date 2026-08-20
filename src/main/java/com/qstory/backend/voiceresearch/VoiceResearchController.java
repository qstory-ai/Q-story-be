package com.qstory.backend.voiceresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.enums.CoverageStatus;
import com.qstory.backend.common.error.EdgeErrorCode;
import com.qstory.backend.common.error.EdgeException;
import com.qstory.backend.common.util.HttpJsonWriter;
import com.qstory.backend.voiceresearch.dto.UploadRequest;
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
@RestController
public class VoiceResearchController {

    private final ObjectMapper objectMapper;
    private final VoiceResearchService service;

    public VoiceResearchController(ObjectMapper objectMapper, VoiceResearchService service) {
        this.objectMapper = objectMapper;
        this.service = service;
    }

    @PostMapping(value = "/v1/voice-research", consumes = "multipart/form-data")
    public void upload(
            @RequestParam("consent_id") String consentId,
            @RequestParam("deletion_token") String deletionToken,
            @RequestParam("consented_at") String consentedAt,
            @RequestParam("sample_id") String sampleId,
            @RequestParam("story_id") String storyId,
            @RequestParam("scene_id") String sceneId,
            @RequestParam("anchor_id") String anchorId,
            @RequestParam("stt_draft") String sttDraft,
            @RequestParam("confirmed_transcript") String confirmedTranscript,
            @RequestParam("question_round") int questionRound,
            @RequestParam("duration_millis") int durationMillis,
            @RequestParam(value = "coverage_status", required = false) String coverageStatus,
            @RequestParam(value = "family_id", required = false) String familyId,
            @RequestParam(value = "intent_summary", required = false) String intentSummary,
            @RequestPart("audio") MultipartFile audio,
            HttpServletResponse response) throws IOException {
        UploadRequest request = new UploadRequest(
                parseUuid(consentId), deletionToken, parseInstant(consentedAt), parseUuid(sampleId), storyId,
                sceneId, anchorId, sttDraft, confirmedTranscript, questionRound, durationMillis, audio,
                parseCoverageStatus(coverageStatus), familyId, intentSummary);
        service.upload(request);
        HttpJsonWriter.writeJson(response, objectMapper, 202, Map.of("ok", true));
    }

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

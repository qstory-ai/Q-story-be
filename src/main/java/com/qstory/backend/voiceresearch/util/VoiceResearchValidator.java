package com.qstory.backend.voiceresearch.util;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.ValidationSupport;
import com.qstory.backend.story.StoryManifest;
import com.qstory.backend.story.service.StoryRegistry;
import com.qstory.backend.voiceresearch.dto.UploadRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Component;

/** voice-research 업로드 라우트에 대한 요청 형식 검증. VoiceResearchService에서 분리했다. */
@Component
public class VoiceResearchValidator {

    private static final Set<String> ALLOWED_MIME_TYPES =
            Set.of("audio/webm", "audio/webm;codecs=opus", "audio/mp4", "audio/m4a", "audio/x-m4a");
    private static final long MAX_AUDIO_BYTES = 3 * 1024 * 1024;

    private final StoryRegistry storyRegistry;

    public VoiceResearchValidator(StoryRegistry storyRegistry) {
        this.storyRegistry = storyRegistry;
    }

    public void validate(UploadRequest request) {
        if (request.consentId() == null || request.sampleId() == null) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "요청 형식이 올바르지 않아요.");
        }
        StoryManifest story = storyRegistry.getBySlug(request.storyId());
        if (story == null
                || isBlank(request.sceneId()) || isBlank(request.anchorId())
                || isBlank(request.sttDraft()) || request.sttDraft().length() > ValidationSupport.MAX_SHORT_TEXT_LENGTH
                || isBlank(request.confirmedTranscript())
                || request.confirmedTranscript().length() > ValidationSupport.MAX_SHORT_TEXT_LENGTH
                || request.questionRound() < 1 || request.questionRound() > 3
                || request.durationMillis() < 250 || request.durationMillis() > 30_000) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "요청 형식이 올바르지 않아요.");
        }
        if (request.audio() == null || request.audio().isEmpty()
                || request.audio().getSize() > MAX_AUDIO_BYTES
                || request.audio().getContentType() == null
                || !ALLOWED_MIME_TYPES.contains(request.audio().getContentType())) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "요청 형식이 올바르지 않아요.");
        }
        if (request.hasRouteOutcome()
                && (request.coverageStatus() == null || isBlank(request.intentSummary())
                        || request.intentSummary().length() > ValidationSupport.MAX_SHORT_TEXT_LENGTH)) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "요청 형식이 올바르지 않아요.");
        }
        if (request.familyId() != null && !isKnownFamilyId(story, request.familyId())) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "요청 형식이 올바르지 않아요.");
        }
        Instant now = Instant.now();
        if (request.consentedAt() == null
                || request.consentedAt().isBefore(now.minus(Duration.ofHours(24)))
                || request.consentedAt().isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw ApiException.contractError(ErrorCode.INVALID_CONSENT_TIME, "동의 시각이 올바르지 않아요.");
        }
    }

    /** familyId는 고정된 목록이 아니라 요청된 스토리 자체의 action family를 기준으로 검증되므로, 어떤 스토리에 대해서도 동작한다. */
    private static boolean isKnownFamilyId(StoryManifest story, String familyId) {
        return story.anchors().values().stream()
                .flatMap(anchor -> anchor.actionFamilies().stream())
                .anyMatch(family -> family.id().equals(familyId));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

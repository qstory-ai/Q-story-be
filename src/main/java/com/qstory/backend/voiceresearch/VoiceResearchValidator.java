package com.qstory.backend.voiceresearch;

import com.qstory.backend.common.error.EdgeErrorCode;
import com.qstory.backend.common.error.EdgeException;
import com.qstory.backend.story.Story;
import com.qstory.backend.story.StoryRegistry;
import com.qstory.backend.voiceresearch.dto.UploadRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Request-shape validation for the voice-research upload route, split out of VoiceResearchService. */
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
            throw new EdgeException(EdgeErrorCode.VALIDATION_FAILED);
        }
        Story story = storyRegistry.getBySlug(request.storyId());
        if (story == null
                || isBlank(request.sceneId()) || isBlank(request.anchorId())
                || isBlank(request.sttDraft()) || request.sttDraft().length() > 240
                || isBlank(request.confirmedTranscript()) || request.confirmedTranscript().length() > 240
                || request.questionRound() < 1 || request.questionRound() > 3
                || request.durationMillis() < 250 || request.durationMillis() > 30_000) {
            throw new EdgeException(EdgeErrorCode.VALIDATION_FAILED);
        }
        if (request.audio() == null || request.audio().isEmpty()
                || request.audio().getSize() > MAX_AUDIO_BYTES
                || request.audio().getContentType() == null
                || !ALLOWED_MIME_TYPES.contains(request.audio().getContentType())) {
            throw new EdgeException(EdgeErrorCode.VALIDATION_FAILED);
        }
        if (request.hasRouteOutcome()
                && (request.coverageStatus() == null || isBlank(request.intentSummary()) || request.intentSummary().length() > 240)) {
            throw new EdgeException(EdgeErrorCode.VALIDATION_FAILED);
        }
        if (request.familyId() != null && !isKnownFamilyId(story, request.familyId())) {
            throw new EdgeException(EdgeErrorCode.VALIDATION_FAILED);
        }
        Instant now = Instant.now();
        if (request.consentedAt() == null
                || request.consentedAt().isBefore(now.minus(Duration.ofHours(24)))
                || request.consentedAt().isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw new EdgeException(EdgeErrorCode.INVALID_CONSENT_TIME);
        }
    }

    /** familyId is validated against the requested story's own action families, not a fixed list, so any story works. */
    private static boolean isKnownFamilyId(Story story, String familyId) {
        return story.anchors().values().stream()
                .flatMap(anchor -> anchor.actionFamilies().stream())
                .anyMatch(family -> family.id().equals(familyId));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

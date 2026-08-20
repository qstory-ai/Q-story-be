package com.qstory.backend.voiceresearch.dto;

import com.qstory.backend.common.enums.CoverageStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public record UploadRequest(
        UUID consentId, String deletionToken, Instant consentedAt, UUID sampleId, String storyId,
        String sceneId, String anchorId, String sttDraft, String confirmedTranscript, int questionRound,
        int durationMillis, MultipartFile audio, CoverageStatus coverageStatus, String familyId, String intentSummary) {

    public boolean hasRouteOutcome() {
        return coverageStatus() != null || familyId() != null || intentSummary() != null;
    }
}

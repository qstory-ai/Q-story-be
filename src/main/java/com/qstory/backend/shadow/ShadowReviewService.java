package com.qstory.backend.shadow;

import com.qstory.backend.common.enums.ReviewStatus;
import com.qstory.backend.persistence.entity.ShadowIntentCandidate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** A human review surface for shadow_intent_candidates - see ShadowReviewController and ShadowIntentCollectionService. */
@Service
public class ShadowReviewService {

    private final ShadowIntentRepository repository;

    public ShadowReviewService(ShadowIntentRepository repository) {
        this.repository = repository;
    }

    public List<ShadowIntentCandidate> readyForReview() {
        return repository.findReadyForReview();
    }

    public ShadowIntentCandidate approve(UUID candidateId, String reviewNote) {
        return setStatus(candidateId, ReviewStatus.APPROVED, reviewNote);
    }

    public ShadowIntentCandidate reject(UUID candidateId, String reviewNote) {
        return setStatus(candidateId, ReviewStatus.REJECTED, reviewNote);
    }

    private ShadowIntentCandidate setStatus(UUID candidateId, ReviewStatus status, String reviewNote) {
        ShadowIntentCandidate candidate = repository.findCandidateById(candidateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        candidate.setReviewStatus(status);
        candidate.setReviewedAt(Instant.now());
        candidate.setUpdatedAt(Instant.now());
        if (reviewNote != null) {
            candidate.setReviewNote(reviewNote);
        }
        return repository.saveCandidate(candidate);
    }
}

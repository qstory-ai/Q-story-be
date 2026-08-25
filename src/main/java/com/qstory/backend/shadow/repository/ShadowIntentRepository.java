package com.qstory.backend.shadow.repository;

import com.qstory.backend.common.enums.ReviewStatus;
import com.qstory.backend.shadow.entity.ShadowIntentCandidate;
import com.qstory.backend.shadow.entity.ShadowQuestionObservation;
import com.qstory.backend.shadow.repository.ShadowIntentCandidateRepository;
import com.qstory.backend.shadow.repository.ShadowQuestionObservationRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** ShadowIntentCollectionService와 ShadowReviewService를 위해 shadow-intent JPA 리포지토리들을 감싼다. */
@Component
public class ShadowIntentRepository {

    private final ShadowIntentCandidateRepository candidateRepository;
    private final ShadowQuestionObservationRepository observationRepository;

    public ShadowIntentRepository(
            ShadowIntentCandidateRepository candidateRepository, ShadowQuestionObservationRepository observationRepository) {
        this.candidateRepository = candidateRepository;
        this.observationRepository = observationRepository;
    }

    public Optional<ShadowIntentCandidate> findCandidate(String storyId, String anchorId, String intentSignature) {
        return candidateRepository.findByStoryIdAndAnchorIdAndIntentSignature(storyId, anchorId, intentSignature);
    }

    public Optional<ShadowIntentCandidate> findCandidateById(UUID candidateId) {
        return candidateRepository.findById(candidateId);
    }

    public ShadowIntentCandidate saveCandidate(ShadowIntentCandidate candidate) {
        return candidateRepository.save(candidate);
    }

    public List<ShadowIntentCandidate> findReadyForReview() {
        return candidateRepository.findByReviewStatusOrderByOccurrenceCountDescLastSeenAtDesc(ReviewStatus.READY_FOR_REVIEW);
    }

    public void saveObservation(ShadowQuestionObservation observation) {
        observationRepository.save(observation);
    }

    public long countObservations(UUID candidateId) {
        return observationRepository.countByCandidate_Id(candidateId);
    }

    public long countDistinctSessions(UUID candidateId) {
        return observationRepository.countDistinctSessionsByCandidateId(candidateId);
    }
}

package com.qstory.backend.shadow;

import com.qstory.backend.common.enums.ReviewStatus;
import com.qstory.backend.persistence.entity.ShadowGenerationJob;
import com.qstory.backend.persistence.entity.ShadowIntentCandidate;
import com.qstory.backend.persistence.entity.ShadowQuestionObservation;
import com.qstory.backend.persistence.repository.ShadowGenerationJobRepository;
import com.qstory.backend.persistence.repository.ShadowIntentCandidateRepository;
import com.qstory.backend.persistence.repository.ShadowQuestionObservationRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Wraps the shadow-intent JPA repositories for ShadowIntentCollectionService and ShadowReviewService. */
@Component
public class ShadowIntentRepository {

    private final ShadowIntentCandidateRepository candidateRepository;
    private final ShadowQuestionObservationRepository observationRepository;
    private final ShadowGenerationJobRepository jobRepository;

    public ShadowIntentRepository(
            ShadowIntentCandidateRepository candidateRepository, ShadowQuestionObservationRepository observationRepository,
            ShadowGenerationJobRepository jobRepository) {
        this.candidateRepository = candidateRepository;
        this.observationRepository = observationRepository;
        this.jobRepository = jobRepository;
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

    public boolean jobExists(UUID candidateId) {
        return jobRepository.findByCandidate_Id(candidateId).isPresent();
    }

    public ShadowGenerationJob saveJob(ShadowGenerationJob job) {
        return jobRepository.save(job);
    }
}

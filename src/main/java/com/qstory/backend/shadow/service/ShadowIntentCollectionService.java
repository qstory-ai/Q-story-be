package com.qstory.backend.shadow.service;
import com.qstory.backend.shadow.repository.ShadowIntentRepository;

import com.qstory.backend.common.enums.ReviewStatus;
import com.qstory.backend.common.util.DigestUtil;
import com.qstory.backend.shadow.entity.ShadowIntentCandidate;
import com.qstory.backend.shadow.entity.ShadowQuestionObservation;
import com.qstory.backend.betaevents.entity.StoryEvent;
import com.qstory.backend.betaevents.entity.StorySession;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Java port of the collect_shadow_intent_from_event / refresh_shadow_intent_candidate Postgres
 * trigger pair (dropped along with the rest of the old schema - this backend owns the behavior
 * in application code instead of DB triggers). Runs synchronously right after a question_result
 * event is persisted; never touches the child-facing runtime path.
 */
@Service
public class ShadowIntentCollectionService {

    private static final String STORY_ID = "hansel-gretel";
    private static final int PROMOTE_THRESHOLD = 3;
    private static final Set<String> COVERAGE_TRIGGERS = Set.of("partial", "uncovered");

    private final ShadowIntentRepository repository;

    public ShadowIntentCollectionService(ShadowIntentRepository repository) {
        this.repository = repository;
    }

    /** Must run inside the same transaction as the StoryEvent insert - see BetaEventService.record(). */
    @Transactional
    public void collectFromQuestionResultEvent(StoryEvent event, StorySession session) {
        var metadata = event.getMetadata();
        if (!"route_accepted".equals(metadata.get("result"))) {
            return;
        }
        Object coverageStatus = metadata.get("coverage_status");
        if (!(coverageStatus instanceof String status) || !COVERAGE_TRIGGERS.contains(status)) {
            return;
        }
        String anchorId = asNonBlankString(metadata.get("anchor_id"));
        String questionIntent = asNonBlankString(metadata.get("question_intent"));
        if (anchorId == null || questionIntent == null || questionIntent.length() > 240) {
            return;
        }

        String normalizedIntent = questionIntent.toLowerCase().replaceAll("[\\s\\p{Punct}]+", " ").trim();
        String signature = DigestUtil.md5Hex(STORY_ID + ":" + anchorId + ":" + normalizedIntent);

        ShadowIntentCandidate candidate = upsertCandidate(anchorId, signature, questionIntent);
        try {
            repository.saveObservation(ShadowQuestionObservation.builder()
                    .event(event).candidate(candidate).session(session).observedAt(Instant.now())
                    .build());
        } catch (DataIntegrityViolationException alreadyObserved) {
            return;
        }

        refreshCandidate(candidate.getId());
    }

    private ShadowIntentCandidate upsertCandidate(String anchorId, String signature, String representativeIntent) {
        return repository.findCandidate(STORY_ID, anchorId, signature)
                .map(existing -> {
                    existing.setRepresentativeIntent(representativeIntent);
                    existing.setLastSeenAt(Instant.now());
                    existing.setUpdatedAt(Instant.now());
                    return existing;
                })
                .orElseGet(() -> {
                    try {
                        Instant now = Instant.now();
                        return repository.saveCandidate(ShadowIntentCandidate.builder()
                                .storyId(STORY_ID).anchorId(anchorId).intentSignature(signature)
                                .representativeIntent(representativeIntent).occurrenceCount(0).distinctSessionCount(0)
                                .reviewStatus(ReviewStatus.COLLECTING).firstSeenAt(now).lastSeenAt(now)
                                .createdAt(now).updatedAt(now)
                                .build());
                    } catch (DataIntegrityViolationException racedInsert) {
                        return repository.findCandidate(STORY_ID, anchorId, signature).orElseThrow(() -> racedInsert);
                    }
                });
    }

    private void refreshCandidate(UUID candidateId) {
        ShadowIntentCandidate candidate = repository.findCandidateById(candidateId).orElse(null);
        if (candidate == null) {
            return;
        }
        long occurrenceCount = repository.countObservations(candidateId);
        long distinctSessionCount = repository.countDistinctSessions(candidateId);
        candidate.setOccurrenceCount((int) occurrenceCount);
        candidate.setDistinctSessionCount((int) distinctSessionCount);
        candidate.setUpdatedAt(Instant.now());

        boolean readyForReview = distinctSessionCount >= PROMOTE_THRESHOLD;
        if (readyForReview
                && (candidate.getReviewStatus() == ReviewStatus.COLLECTING
                        || candidate.getReviewStatus() == ReviewStatus.READY_FOR_REVIEW)) {
            candidate.setReviewStatus(ReviewStatus.READY_FOR_REVIEW);
        }
        repository.saveCandidate(candidate);
    }

    private static String asNonBlankString(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }
}

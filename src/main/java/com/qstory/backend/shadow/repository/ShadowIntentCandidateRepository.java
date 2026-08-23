package com.qstory.backend.shadow.repository;

import com.qstory.backend.common.enums.ReviewStatus;
import com.qstory.backend.shadow.entity.ShadowIntentCandidate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShadowIntentCandidateRepository extends JpaRepository<ShadowIntentCandidate, UUID> {

    Optional<ShadowIntentCandidate> findByStoryIdAndAnchorIdAndIntentSignature(
            String storyId, String anchorId, String intentSignature);

    List<ShadowIntentCandidate> findByReviewStatusOrderByOccurrenceCountDescLastSeenAtDesc(ReviewStatus reviewStatus);
}

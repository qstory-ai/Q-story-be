package com.qstory.backend.shadow.repository;

import com.qstory.backend.common.enums.ReviewStatus;
import com.qstory.backend.shadow.entity.ShadowFamilyDraft;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShadowFamilyDraftRepository extends JpaRepository<ShadowFamilyDraft, UUID> {

    Optional<ShadowFamilyDraft> findByCandidate_Id(UUID candidateId);

    /** anchor당 하나만 노출한다(가장 최근 승인) - 라우팅 후보 목록을 작게, 예측 가능하게 유지하기 위함. */
    List<ShadowFamilyDraft> findByCandidate_AnchorIdAndReviewStatusOrderByGeneratedAtDesc(
            String anchorId, ReviewStatus reviewStatus);
}

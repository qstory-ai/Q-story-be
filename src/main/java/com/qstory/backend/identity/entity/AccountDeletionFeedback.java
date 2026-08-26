package com.qstory.backend.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * 마이페이지 "회원 탈퇴" 설문 한 건 - AppUser를 소프트 삭제하기 직전에 남긴다. userId는 관계가
 * 아닌 plain 컬럼이다(CompletionSurvey/ImprovementFeedback과 동일한 스타일) - 탈퇴 후에도 그
 * 계정이 어떤 역할이었는지 알 수 있도록 role은 조회 시점이 아니라 탈퇴 시점 스냅샷으로 저장한다.
 */
@Entity
@Table(name = "account_deletion_feedback")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountDeletionFeedback {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String role;

    @Column(name = "reason_category", nullable = false)
    private String reasonCategory;

    @Column(name = "reason_detail")
    private String reasonDetail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

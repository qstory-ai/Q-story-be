package com.qstory.backend.completionsurvey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * 완주 후 부모가 남기는 "1분 체험 후기" 한 건 - LaunchNotificationRequest와 마찬가지로 로그인
 * 계정과 무관한 익명 제출이다(무료 데모는 로그인이 없다). 문항은 기존 외부 Google Form과
 * 동일하며(CompletionSurveyController 참고), 원본 음성이나 전체 리포트 텍스트는 포함하지 않는다.
 */
@Entity
@Table(name = "completion_surveys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompletionSurvey {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "story_id", nullable = false, length = 64)
    private String storyId;

    @Column(name = "child_age_band", nullable = false, length = 20)
    private String childAgeBand;

    @Column(name = "child_engagement", nullable = false)
    private int childEngagement;

    @Column(name = "input_understanding", nullable = false, length = 100)
    private String inputUnderstanding;

    @Column(name = "help_needed", nullable = false, length = 100)
    private String helpNeeded;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "child_reactions", nullable = false, columnDefinition = "jsonb")
    private List<String> childReactions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> disruptions;

    @Column(name = "report_helpfulness", nullable = false)
    private int reportHelpfulness;

    @Column(name = "best_aspect", nullable = false, length = 200)
    private String bestAspect;

    @Column(name = "top_priority", length = 500)
    private String topPriority;

    @Column(name = "retry_interest", nullable = false, length = 100)
    private String retryInterest;

    @Column(name = "one_line_review", length = 500)
    private String oneLineReview;

    @Column(name = "review_usage_consent", nullable = false, length = 100)
    private String reviewUsageConsent;

    @Column(name = "wants_next_stories", nullable = false, length = 100)
    private String wantsNextStories;

    @Column(length = 254)
    private String contact;

    @Column(name = "contact_consent", nullable = false, length = 100)
    private String contactConsent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

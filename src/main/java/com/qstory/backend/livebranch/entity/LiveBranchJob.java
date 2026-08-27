package com.qstory.backend.livebranch.entity;

import com.qstory.backend.common.enums.LiveBranchJobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * 아이 질문이 안전하지만 어떤 기존 콘텐츠에도 연결되지 못했을 때 route_classifier가 NEW_CHOICES를
 * 고르면(QuestionRoutingService 참고) 큐에 들어가는 실시간 새 선택지 생성 작업. ShadowFamilyDraft와
 * 달리 생성 결과 JSON을 여기에 스테이징하지 않는다 - 성공하면 LiveBranchExecutionWorker가 바로 실제
 * 콘텐츠 테이블(story_action_family/story_fallback_segment/story_asset)에 커밋하기 때문이다. 이
 * 테이블은 그 작업의 생명주기(QUEUED -> GENERATING -> READY|FAILED)만 추적한다.
 *
 * <p>Phase 2부터 한 job이 family 하나가 아니라 최대 3개를 만든다(새로 생성된 것 + 부족분을 채우는
 * 기존 family) - 그래서 단일 resultFamilyId 대신 정확히 3개(성공적으로 채워졌다면)의
 * {familyId, label, meaning}을 담는 resultOptions로 바뀌었다(LiveBranchController의 wire 모양도
 * 동일하게 바뀜).
 */
@Entity
@Table(name = "live_branch_job")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LiveBranchJob {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "story_id", nullable = false)
    private String storyId;

    @Column(name = "anchor_id", nullable = false)
    private String anchorId;

    /** ShadowFamilyGenerationService.redact()와 동일한 규칙으로 이메일/전화/URL/이름을 지운 아이 발화. */
    @Column(name = "child_transcript_redacted", nullable = false, length = 500)
    private String childTranscriptRedacted;

    @Column(name = "question_round", nullable = false)
    private int questionRound;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LiveBranchJobStatus status;

    /**
     * READY일 때만 채워지는, 정확히 3개(성공적으로 채워졌다면)의 {@code {familyId, label, meaning}}
     * 목록 - 새로 생성된 family와 부족분을 채운 기존 family가 순서 없이 섞여 있다(LiveBranchController
     * 참고).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_options_json", columnDefinition = "jsonb")
    private List<Map<String, Object>> resultOptions;

    /** FAILED일 때만 채워진다. */
    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

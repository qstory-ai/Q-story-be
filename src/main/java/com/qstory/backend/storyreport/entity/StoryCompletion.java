package com.qstory.backend.storyreport.entity;

import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.parent.child.entity.Child;
import com.qstory.backend.tutor.entity.TutorStudent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * 완료된 스토리 세션 하나에 대한 보호자용 리포트로, 보호자가 나중에 다시 볼 수 있도록 보관한다 - 그렇지
 * 않으면 "오늘의 질문 기록" 화면은 그 화면을 벗어나는 순간 사라져버렸을 것이다.
 *
 * <p>outcomes는 프론트엔드의 QuestionOutcome[](entities/analytics/model/parent-report.ts 참고)을
 * 그대로 반영한다 - 해당 화면이 이미 자신의 리포트를 만들 때 사용하는 것과 동일한, anchor별로 파생된
 * 요약 텍스트(childRelevantMeaning, route, selectedOption)이며, 원본 음성 녹음이나 트랜스크립트는
 * 절대 포함하지 않는다. 전체 리포트 텍스트 자체는 저장되지 않는다; ParentReportPanel.buildParentReport()가
 * 조회 시점에 이 데이터와 스토리 자체의 reportCopy로부터 리포트를 다시 만들어내며, 이는 방금 완료된
 * 세션에 대해 하는 것과 동일한 방식이다.
 *
 * <p>tutorStudent는 이 세션이 방문 선생님이 그 학생과 진행한 수업이면 채워지고, 가정에서 부모가
 * 자유롭게 본 세션이면 null이다 - "누가 진행했는지"를 나타내는 별도 플래그를 새로 두는 대신, user가
 * 이미 실제로 세션을 진행한 계정(선생님이 진행하면 user=선생님)이라는 사실을 그대로 활용한다.
 * 이 구분 하나로 "선생님이 진행한 수업만" 부모에게 공유하는 게 가능해진다(TutorReportService 참고).
 */
@Entity
@Table(name = "story_completion")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryCompletion {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tutor_student_id")
    private TutorStudent tutorStudent;

    /**
     * 이 세션이 어느 아이 프로필로 진행됐는지 - 부모(PARENT) 계정의 아이별 리포트 필터에 쓴다.
     * nullable 이유는 037-story-completion-child.sql 헤더 참조: 기존 완주 기록, 방문 선생님의
     * 세션, 아이 프로필 삭제 이후 세 경우에 null이 된다. 삭제는 SET NULL이라 아이가 지워져도
     * 완주 기록 자체는 남는다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "child_id")
    private Child child;

    @Column(name = "story_id", nullable = false)
    private String storyId;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> outcomes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

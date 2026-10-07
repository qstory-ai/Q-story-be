package com.qstory.backend.storyreport.entity;

import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.parent.child.entity.Child;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.entity.Lesson;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
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
 * <p>세션은 세 종류다. 가정 세션(부모가 진행, participants 비어 있음), 개별 수업(선생님이 학생 한 명과
 * 진행, tutorStudent·child 채워짐), 반 수업(groupSession, participants가 참여 학생 전원). 부모는
 * participants로 연결된 선생님 세션만 "수업 리포트"로 보고, 가정 기록과는 섞지 않는다(TutorReportService 참고).
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

    /** Membership snapshot for historical institution reports after a parent changes classes. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_group_id")
    private ClassGroup classGroup;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tutor_student_id")
    private TutorStudent tutorStudent;

    /**
     * 이 세션이 어느 아이 프로필로 진행됐는지 - 부모(PARENT) 계정의 아이별 리포트 필터에 쓴다.
     * nullable 이유는 037-story-completion-child.sql 헤더 참조: 기존 완주 기록, 선생님의
     * 세션, 아이 프로필 삭제 이후 세 경우에 null이 된다. 삭제는 SET NULL이라 아이가 지워져도
     * 완주 기록 자체는 남는다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "child_id")
    private Child child;

    /**
     * 선생님 세션에 참여한 학생(059). 개별 수업은 tutorStudent 한 명, 반 수업은 참여 학생 전원이다.
     * 부모의 열람 권한과 "수업 리포트" 목록은 이 목록의 linkedParentUser로 정한다. 가정 세션은 비어 있다.
     */
    @ManyToMany
    @JoinTable(
            name = "story_completion_participant",
            joinColumns = @JoinColumn(name = "completion_id"),
            inverseJoinColumns = @JoinColumn(name = "tutor_student_id"))
    @BatchSize(size = 50)
    @Builder.Default
    private Set<TutorStudent> participants = new LinkedHashSet<>();

    /**
     * 반 수업 기록(059) - 여러 아이가 한 화면으로 함께 읽은 세션 하나를 한 행으로 남긴다. 발화가 누구의
     * 것인지 알 수 없으므로 tutorStudent·child는 비워 두고, 부모에게는 "우리 반 수업 리포트"로 보여 준다.
     */
    @Column(name = "group_session", nullable = false)
    private boolean groupSession;

    /** 수업 상세에서 시작한 세션이면 그 수업(050). 수업이 삭제되면 null로 남는다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_id")
    private Lesson lesson;

    /**
     * 프론트가 이야기 세션당 하나 만드는 conversationId(053). 같은 세션의 재시도 저장을 같은 기록으로
     * 되돌려 주기 위한 멱등 키 - 반 수업은 (session_id, tutor_student_id), 가정 세션은 session_id 단위.
     */
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "story_id", nullable = false)
    private String storyId;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> outcomes;

    /**
     * 상시 대화(companion-chat) 태그 집계 스냅샷 - 완주 시점의 conversationId에 해당하는
     * companion_chat_turn 행들을 topic/tone/value/turnCount로 접어 넣은 결과다. 리포트
     * 조회마다 다시 계산하지 않는 이유는 046-story-completion-companion-summary.sql 헤더 참고.
     * 상시 대화 없이 진행한 세션이나 legacy 기록에서는 null.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "companion_chat_summary", columnDefinition = "jsonb")
    private Map<String, Object> companionChatSummary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 읽은 작품 버전(story.yaml contentVersion) - 콘텐츠가 바뀐 뒤에도 그때 장면으로 리포트를 그리기 위해(069). */
    @Column(name = "content_version")
    private String contentVersion;

    /** COMPLETED(끝까지 읽음) | EXITED(중간에 나감). 같은 회차를 이어 읽어 끝내면 COMPLETED로 올라간다. */
    @Column(name = "end_status", nullable = false)
    @Builder.Default
    private String endStatus = "COMPLETED";

    @Column(name = "read_from_scene_id")
    private String readFromSceneId;

    @Column(name = "read_through_scene_id")
    private String readThroughSceneId;

    /** 선생님만 보는 수업 메모. 부모 화면에는 절대 내보내지 않는다. */
    @Column(name = "teacher_note_internal")
    private String teacherNoteInternal;

    /** 부모에게 공유하는 선생님 한마디. */
    @Column(name = "teacher_note_for_parents")
    private String teacherNoteForParents;

    /** 리포트 화면의 구분 - CLASS(반 수업), TUTOR(선생님 개별 수업), HOME(집에서 읽은 기록). */
    public String sessionKind() {
        if (groupSession) return "CLASS";
        if (lesson != null || tutorStudent != null || !participants.isEmpty()) return "TUTOR";
        return "HOME";
    }
}

package com.qstory.backend.tutor.lesson.entity;

import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.LessonStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

/**
 * IA "[3] 수업"의 최상위 엔티티. 이름/목표(선택)/일정(선택) + 참여 학생 + 사용할 이야기를 묶는다.
 * lesson_story의 순서는 별도 조인 엔티티가 아니라 @OrderColumn/@OrderBy로 다루려면 조인 엔티티가
 * 필요한데 스토리 M:N에는 그 정보가 다행히 크게 안 중요해서(선생님이 UI에서 순서를 새로 잡는다)
 * ordinal은 raw SQL에만 두고 엔티티에는 노출하지 않는다 - 이번 세션 범위. 필요해지면 조인 엔티티로.
 */
@Entity
@Table(name = "lesson")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lesson {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tutor_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser tutor;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(columnDefinition = "text")
    private String goal;

    /** null이면 "일정 미정" - IA는 goal과 함께 null 허용을 명시했다. */
    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    /**
     * 정기 수업 제출 한 번(N개의 개별 create 호출)이 공유하는 클라이언트 생성 UUID - 단발성
     * 수업은 null. "이 수업만" vs "향후 모든 수업"을 구분해 수정하려면 같은 시리즈의 형제
     * lesson들을 찾을 수 있어야 하는데, 이 컬럼이 그 유일한 연결고리다(041-lesson-series.sql
     * 참고). 서버는 값을 생성하지 않고 그대로 저장만 한다.
     */
    @Column(name = "series_id")
    private UUID seriesId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private LessonStatus status = LessonStatus.SCHEDULED;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToMany
    @JoinTable(
            name = "lesson_student",
            joinColumns = @JoinColumn(name = "lesson_id"),
            inverseJoinColumns = @JoinColumn(name = "tutor_student_id"))
    @OrderBy("createdAt asc")
    @Builder.Default
    private Set<TutorStudent> students = new LinkedHashSet<>();

    /**
     * 사용 이야기는 스토리 엔티티(Story)와 M:N이지만 story_id가 문자열이라 collection table을 쓴다
     * (@ElementCollection). CascadeType.ALL과 orphanRemoval을 두어 lesson과 함께 라이프사이클을
     * 관리 - 별도 서비스가 관여하지 않게.
     */
    @jakarta.persistence.ElementCollection(fetch = FetchType.LAZY)
    @jakarta.persistence.CollectionTable(
            name = "lesson_story",
            joinColumns = @JoinColumn(name = "lesson_id"))
    @Column(name = "story_id", length = 64, nullable = false)
    @Builder.Default
    private Set<String> storyIds = new HashSet<>();

    // Cascade는 join 관계에는 걸지 않는다 - lesson_student/lesson_story 행은 자동 정리되지만,
    // 참조 대상(TutorStudent/Story)은 다른 곳에서 소유되므로 여기서 삭제해선 안 된다.
    @SuppressWarnings("unused")
    private static final CascadeType[] NO_CASCADE_FOR_JOIN_TARGETS = {};
}

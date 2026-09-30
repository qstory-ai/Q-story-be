package com.qstory.backend.tutor.lesson.entity;

import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.LessonStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

/**
 * IA "[3] 수업"의 최상위 엔티티. 이름/목표(선택)/일정(선택) + 참여 학생 + 사용할 이야기를 묶는다.
 * lesson_story의 ordinal 컬럼은 엔티티에 노출하지 않는다 - 순서는 선생님이 UI에서 새로 잡으므로
 * 조인 엔티티를 둘 만큼 중요하지 않다.
 */
@Entity
@Table(name = "lesson")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lesson {

    /** 수업 목록에서 LessonResponse가 lesson마다 students/storyIds를 읽는 N+1을 묶어서 가져온다. */
    private static final int LIST_BATCH_SIZE = 50;

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

    /** 반 수업이면 어느 반인지(049). 개인 레슨·여러 학생을 직접 고른 수업은 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_group_id")
    private ClassGroup classGroup;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 참여 학생. 조인 테이블 행만 lesson과 함께 정리되고 TutorStudent 자체에는 cascade를 걸지 않는다. */
    @ManyToMany
    @JoinTable(
            name = "lesson_student",
            joinColumns = @JoinColumn(name = "lesson_id"),
            inverseJoinColumns = @JoinColumn(name = "tutor_student_id"))
    @OrderBy("createdAt asc")
    @BatchSize(size = LIST_BATCH_SIZE)
    @Builder.Default
    private Set<TutorStudent> students = new LinkedHashSet<>();

    /**
     * 사용 이야기. story_id가 FK 없는 문자열이라 collection table(@ElementCollection)로 두며, lesson_story
     * 행은 lesson과 라이프사이클을 같이 한다.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "lesson_story",
            joinColumns = @JoinColumn(name = "lesson_id"))
    @Column(name = "story_id", length = 64, nullable = false)
    @BatchSize(size = LIST_BATCH_SIZE)
    @Builder.Default
    private Set<String> storyIds = new HashSet<>();
}

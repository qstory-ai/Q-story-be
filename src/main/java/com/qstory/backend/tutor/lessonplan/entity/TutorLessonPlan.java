package com.qstory.backend.tutor.lessonplan.entity;

import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.tutor.entity.TutorStudent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
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
 * 방문 선생님이 특정 학생의 다음 수업에 쓸 이야기를 담아 두는 한 행. 서재의 "수업에 사용하기"
 * 버튼이 여기 하나를 추가한다. story_id는 FK가 아니라 varchar(64) - story_completion/bookmark와
 * 같은 규약(콘텐츠가 시드 전이거나 회수된 경우도 참조를 유지).
 *
 * <p>tutor는 tutor_student.tutor와 중복이지만, 각 학생을 로드하지 않고 "선생님 이번 주에 담긴
 * 이야기" 쿼리를 바로 할 수 있게 두 관계를 모두 남겼다 - 소유권 검증도 tutor_id 하나만 보고
 * 되도록 한다.
 */
@Entity
@Table(name = "tutor_lesson_plan",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tutor_student_id", "story_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TutorLessonPlan {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tutor_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser tutor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tutor_student_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TutorStudent tutorStudent;

    @Column(name = "story_id", nullable = false, length = 64)
    private String storyId;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;
}

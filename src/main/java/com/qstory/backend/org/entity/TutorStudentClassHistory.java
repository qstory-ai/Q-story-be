package com.qstory.backend.org.entity;

import com.qstory.backend.tutor.entity.TutorStudent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 학생의 반 소속 한 구간(076) - 이 학생이 startedAt부터 endedAt까지 이 반이었다. endedAt이 null이면 지금 반.
 * 원장이 학생을 옮겨도 학생 행은 그대로라 지난 기록이 이어지고, 언제 어느 반이었는지는 이 이력으로 본다.
 * reason은 구간이 시작된 이유, endReason은 끝난 이유다(값은 {@link ClassMembershipReason}).
 */
@Entity
@Table(name = "tutor_student_class_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TutorStudentClassHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tutor_student_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TutorStudent tutorStudent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_group_id", nullable = false)
    private ClassGroup classGroup;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(nullable = false)
    private String reason;

    @Column(name = "end_reason")
    private String endReason;

    /** 바꾼 사람(원장). 반 코드로 들어온 구간처럼 사람이 바꾼 게 아니면 null. */
    @Column(name = "changed_by")
    private UUID changedBy;
}

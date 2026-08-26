package com.qstory.backend.tutor.entity;

import com.qstory.backend.tutor.Weekday;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * 학생 한 명의 매주 반복 수업 일정 - 지금은 "매주 반복" 한 종류만 지원하고, 단건 시간 변경/휴강/
 * 반복 수정 같은 예외 처리는 다음 단계로 미룬다(프로토타입도 카피로만 존재하고 구현돼 있지 않음).
 * 학생당 여러 건 등록 가능하도록 학생을 소유자로 두는 별도 테이블로 뺐다(TutorStudent에 인라인
 * 컬럼으로 두지 않음).
 */
@Entity
@Table(name = "tutor_schedule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TutorSchedule {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tutor_student_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TutorStudent tutorStudent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Weekday weekday;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private String location;

    @Column(name = "reminder_enabled", nullable = false)
    @Builder.Default
    private boolean reminderEnabled = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}

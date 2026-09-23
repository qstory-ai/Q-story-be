package com.qstory.backend.tutor.entity;

import com.qstory.backend.common.util.ChildAge;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.parent.child.entity.Child;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.tutor.TutorLessonType;
import com.qstory.backend.tutor.TutorStudentStatus;
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
 * 선생님(TUTOR)이 등록한 학생 한 명 - ClassGroup이 Organization에 속하듯, 이 엔티티는 TUTOR
 * 역할의 AppUser에 속한다. 등록 시점엔 별명/연령대/수업 메모만 저장되고(부모 계정과 아직 연결되지
 * 않음), TutorInvite를 통해 부모가 수락해야 linkedParentUser가 채워지고 status가 CONFIRMED로
 * 바뀐다 - 그 전까지는 리포트 전달이 활성화되지 않는다(TutorStudentService 참고).
 */
@Entity
@Table(name = "tutor_student")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TutorStudent {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tutor_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser tutor;

    @Column(nullable = false)
    private String name;

    /** 저장 시점의 "N세" 라벨. birthYear가 있으면 {@link #currentAgeBand()}가 매번 다시 계산한다. */
    @Column(name = "age_band", nullable = false)
    private String ageBand;

    /** ~년생(051). 예전 행은 null이라 그때는 저장된 ageBand를 그대로 쓴다. */
    @Column(name = "birth_year")
    private Integer birthYear;

    @Column(name = "class_type")
    private String classType;

    @Column(name = "prep_note")
    private String prepNote;

    /** 개인 레슨인지 반 수업인지. CLASS면 classGroup이 채워진다(049). */
    @Enumerated(EnumType.STRING)
    @Column(name = "lesson_type", nullable = false)
    @Builder.Default
    private TutorLessonType lessonType = TutorLessonType.INDIVIDUAL;

    /** lessonType이 CLASS일 때 속한 반. 반이 삭제되면 null로 돌아가고 lessonType은 그대로 남는다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_group_id")
    private ClassGroup classGroup;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TutorStudentStatus status = TutorStudentStatus.PENDING_PARENT;

    /** 부모가 초대를 수락하기 전까지는 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_parent_user_id")
    private AppUser linkedParentUser;

    /**
     * 부모가 초대를 수락할 때 연결(또는 생성)되는 부모 쪽 아이 프로필(parent_child). 예전엔 부모
     * 계정만 연결하고 아이 행은 만들지 않아 "수락했는데 아이가 등록되지 않는" 상태가 됐다. 이 링크가
     * 있어야 선생님 세션의 완주 기록이 아이 프로필로 이어진다(StoryCompletionService 참고).
     * 아이 프로필이 삭제되면 null로 돌아간다(048 마이그레이션 on delete set null).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "child_id")
    private Child child;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public String currentAgeBand() {
        return birthYear == null ? ageBand : ChildAge.tutorLabel(birthYear);
    }
}

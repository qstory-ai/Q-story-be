package com.qstory.backend.tutor.entity;

import com.qstory.backend.identity.entity.AppUser;
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
 * 방문 선생님(TUTOR)이 등록한 학생 한 명 - ClassGroup이 Organization에 속하듯, 이 엔티티는 TUTOR
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

    @Column(name = "age_band", nullable = false)
    private String ageBand;

    @Column(name = "class_type")
    private String classType;

    @Column(name = "prep_note")
    private String prepNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TutorStudentStatus status = TutorStudentStatus.PENDING_PARENT;

    /** 부모가 초대를 수락하기 전까지는 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_parent_user_id")
    private AppUser linkedParentUser;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}

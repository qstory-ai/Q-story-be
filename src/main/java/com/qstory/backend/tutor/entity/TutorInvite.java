package com.qstory.backend.tutor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 학생 한 명에 대한 1회용, 만료 가능한 부모 초대 - org.entity.ClassInvite와 완전히 같은 모양이다:
 * 원본 토큰은 발급 시 한 번만 반환되고 절대 저장되지 않으며, tokenHash만 저장된다.
 */
@Entity
@Table(name = "tutor_invite")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TutorInvite {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tutor_student_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TutorStudent tutorStudent;

    @Column(nullable = false, unique = true)
    private String tokenHash;

    /** "SMS" 또는 "LINK" - 초대를 어떻게 전달했는지 기록용(재전송 로직은 없음). */
    @Column(nullable = false)
    private String method;

    private String phoneNumber;

    @Column(nullable = false)
    private Instant expiresAt;

    /** 사용되기 전까지는 null이며, 이미 사용된 초대는 다시 사용할 수 없다. */
    private Instant usedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}

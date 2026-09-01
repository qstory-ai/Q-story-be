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

    /**
     * 사람이 손으로 옮길 수 있는 짧은 코드(예: 8자, ClassGroup.joinCode와 같은 알파벳).
     * 링크(token)와 함께 발급된다 - 부모가 링크를 열 수 없거나 선생님이 구두로 전달할 때 쓴다.
     * nullable 이유: 이 컬럼이 추가되기 전에 발급된 이력 행은 null로 남는다(마이그레이션 참조).
     * 새 초대 발급 시에는 반드시 함께 채운다.
     */
    @Column(name = "short_code", unique = true)
    private String shortCode;

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

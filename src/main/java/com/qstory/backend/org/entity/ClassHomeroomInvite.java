package com.qstory.backend.org.entity;

import com.qstory.backend.identity.entity.AppUser;
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
 * 반 담임 초대(db/schema/074). 선생님이 이 코드로 수락하면 기관에 소속되고 그 반의 담임이 된다. 1회용, 만료 14일.
 * 반마다 쓰지 않은 초대는 하나뿐이다 - 새로 발급하면 이전 것을 지운다.
 */
@Entity
@Table(name = "class_homeroom_invite")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClassHomeroomInvite {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_group_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ClassGroup classGroup;

    @Column(name = "token", nullable = false, unique = true, length = 255)
    private String token;

    @Column(name = "short_code", nullable = false, unique = true, length = 16)
    private String shortCode;

    /** 발급한 원장. 계정이 지워지면 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 사용되기 전까지는 null. 사용 후 다시 사용할 수 없다. */
    @Column(name = "used_at")
    private Instant usedAt;

    /** 수락한 선생님. 계정이 지워지면 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "used_by")
    private AppUser usedBy;
}

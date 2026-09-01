package com.qstory.backend.org.tutor.entity;

import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.org.entity.Organization;
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
 * 기관-선생님 소속 관계 한 건. IA "기관 관리자 > 선생님 관리"의 기본 단위. AppUser.organization
 * (DIRECTOR/CLASS_ACCOUNT 필드)와 별개로 두는 이유는 TUTOR 한 명이 이론상 여러 기관에 소속될
 * 수 있게 열어 두려는 것 - 지금 UI는 한 기관만 노출한다.
 */
@Entity
@Table(name = "organization_tutor",
        uniqueConstraints = @UniqueConstraint(columnNames = {"organization_id", "tutor_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationTutor {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tutor_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser tutor;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;
}

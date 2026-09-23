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
 * 한 반(classroom). joinCode는 영구적/재사용 가능하며 - 자율 가입용으로 전단지에 인쇄할 수 있다.
 *
 * <p>소유자는 기관(organization) 또는 선생님(tutor), 혹은 둘 다(기관 소속 선생님이 기관 안에 만든 반).
 * 둘 중 하나는 반드시 있다(049 마이그레이션 check). 기관이 없는 선생님 개인 반은 부모 가입 코드·반
 * 계정 같은 기관 전용 흐름에서 제외된다(ClassService.requireOrganizationClass).
 */
@Entity
@Table(name = "class_group")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClassGroup {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Organization organization;

    /** 이 반을 만든 선생님. 기관 관리자(DIRECTOR)가 만든 반은 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tutor_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser tutor;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String joinCode;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}

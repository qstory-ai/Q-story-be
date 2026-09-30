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
 * 한 반(classroom). 기관 반은 기관 → 담임 선생님 → 학생들, 기관 없는 선생님 반은 선생님 → 학생들이다.
 * joinCode는 영구적/재사용 가능하며 학부모 가입용으로 전단지에 인쇄할 수 있다.
 *
 * <p>소유자는 기관(organization) 또는 선생님(tutor), 혹은 둘 다(기관 소속 선생님이 담임인 반).
 * 둘 중 하나는 반드시 있다(049 마이그레이션 check). 기관 반에 담임이 아직 없으면 tutor가 null이고
 * (원장이 배정하기 전), 그 반의 학생은 담임이 정해질 때까지 tutor 없이 명단에만 올라 있다.
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

    /** 담임 선생님. 기관 반에서 아직 배정되지 않았으면 null. */
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

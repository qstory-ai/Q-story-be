package com.qstory.backend.identity.entity;

import com.qstory.backend.identity.Role;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.Organization;
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
import org.hibernate.annotations.UuidGenerator;

/**
 * One login account, covering all three roles (DIRECTOR/CLASS_ACCOUNT/PARENT) - a single table
 * with a role discriminator rather than per-role tables or @Inheritance, since login is always a
 * single role-agnostic loginId lookup and the three roles' fields overlap heavily (see the auth
 * plan doc). organization/classGroup are nullable depending on role: a DIRECTOR has no classGroup
 * (and no organization until they create one); CLASS_ACCOUNT/PARENT always have both.
 *
 * <p>Deliberately not cascade-deleted from Organization/ClassGroup - deleting an org/class must
 * never silently remove someone's login (moot for now: neither has a delete endpoint yet).
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser {

    @Id
    @UuidGenerator
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    /** Email for DIRECTOR/PARENT; a director-chosen handle for CLASS_ACCOUNT - distinct from ClassGroup.joinCode. */
    @Column(nullable = false, unique = true)
    private String loginId;

    /** BCrypt hash. Nullable to future-proof an OAuth-only account (e.g. Kakao login) added later. */
    private String passwordHash;

    @Column(nullable = false)
    private String displayName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_group_id")
    private ClassGroup classGroup;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}

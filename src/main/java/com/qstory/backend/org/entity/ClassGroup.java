package com.qstory.backend.org.entity;

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

/** Organization 내부의 한 반(classroom). joinCode는 영구적/재사용 가능하며 - 자율 가입용으로 전단지에 인쇄할 수 있다. */
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Organization organization;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String joinCode;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}

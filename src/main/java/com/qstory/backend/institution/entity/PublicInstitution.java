package com.qstory.backend.institution.entity;

import com.qstory.backend.institution.InstitutionKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 공공데이터 기관 목록 한 곳(083). 앱은 읽기만 한다 - 행은 PublicInstitutionImportMain이 넣고 고친다.
 * 검색은 PublicInstitutionSearchService가 SQL로 하고, 여기서는 원장이 고른 기관 한 곳을 읽을 때 쓴다.
 */
@Entity
@Immutable
@Table(name = "public_institution")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublicInstitution {

    public static final String STATUS_ACTIVE = "ACTIVE";

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InstitutionKind kind;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 300)
    private String address;

    @Column(length = 40)
    private String sido;

    @Column(length = 40)
    private String sigungu;

    @Column(name = "establishment_type", length = 40)
    private String establishmentType;

    @Column(nullable = false, length = 16)
    private String status;

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }
}

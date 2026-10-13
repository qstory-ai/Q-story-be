package com.qstory.backend.org.dto;

import com.qstory.backend.institution.entity.PublicInstitution;
import com.qstory.backend.org.entity.Organization;
import java.util.UUID;

/** 원장이 보는 우리 기관 - 공공 기관 목록과 연결됐으면 그곳(이름·주소·종류)을 함께. */
public record OrganizationProfileResponse(UUID id, String name, LinkedInstitution publicInstitution) {

    public record LinkedInstitution(UUID id, String kind, String name, String address, String establishmentType) {}

    public static OrganizationProfileResponse of(Organization organization, PublicInstitution institution) {
        return new OrganizationProfileResponse(
                organization.getId(),
                organization.getName(),
                institution == null ? null : new LinkedInstitution(
                        institution.getId(), institution.getKind().name(), institution.getName(),
                        institution.getAddress(), institution.getEstablishmentType()));
    }
}

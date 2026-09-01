package com.qstory.backend.org.tutor.dto;

import com.qstory.backend.org.tutor.entity.OrganizationTutor;
import java.time.Instant;
import java.util.UUID;

/**
 * 선생님 관점의 소속 - "내가 어느 기관에 속해 있는지". OrganizationTutorResponse가 관리자 관점
 * (기관이 어떤 선생님을 데리고 있는지)이라 짝을 이룬다. 두 DTO 모두 같은 OrganizationTutor
 * 행에서 나오지만, 어느 쪽에서 읽느냐에 따라 무엇을 보여줘야 하는지가 다르다.
 */
public record TutorOrganizationResponse(UUID id, UUID organizationId, String organizationName, Instant joinedAt) {

    public static TutorOrganizationResponse of(OrganizationTutor link) {
        return new TutorOrganizationResponse(
                link.getId(),
                link.getOrganization().getId(),
                link.getOrganization().getName(),
                link.getJoinedAt());
    }
}

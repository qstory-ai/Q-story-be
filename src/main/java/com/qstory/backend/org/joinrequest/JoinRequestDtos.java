package com.qstory.backend.org.joinrequest;

import java.time.Instant;
import java.util.UUID;

/** 소속 신청 API의 요청·응답 모양. */
public final class JoinRequestDtos {

    private JoinRequestDtos() {}

    public record CreateJoinRequest(UUID organizationId) {}

    /** 원장이 보는 신청 - 선생님 이름과 이메일(초대 수락 뒤 선생님 목록과 같은 정도). */
    public record OrganizationJoinRequestResponse(
            UUID id, UUID tutorId, String tutorDisplayName, String tutorEmail, String status,
            Instant createdAt, Instant decidedAt) {

        static OrganizationJoinRequestResponse of(OrganizationJoinRequest request) {
            var tutor = request.getTutor();
            return new OrganizationJoinRequestResponse(
                    request.getId(), tutor.getId(), tutor.getDisplayName(), tutor.getEmail(),
                    request.getStatus().name(), request.getCreatedAt(), request.getDecidedAt());
        }
    }

    /** 선생님이 보는 내 신청. */
    public record TutorJoinRequestResponse(
            UUID id, UUID organizationId, String organizationName, String status,
            Instant createdAt, Instant decidedAt) {

        static TutorJoinRequestResponse of(OrganizationJoinRequest request) {
            return new TutorJoinRequestResponse(
                    request.getId(), request.getOrganization().getId(), request.getOrganization().getName(),
                    request.getStatus().name(), request.getCreatedAt(), request.getDecidedAt());
        }
    }
}

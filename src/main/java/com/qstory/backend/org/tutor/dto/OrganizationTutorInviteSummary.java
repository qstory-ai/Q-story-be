package com.qstory.backend.org.tutor.dto;

import com.qstory.backend.org.tutor.entity.OrganizationTutorInvite;
import java.time.Instant;
import java.util.UUID;

/**
 * 발급 이력 조회용 요약 - 원본 token은 여기 절대 실리지 않는다(발급 시점 응답에만 반환).
 * shortCode는 관리자 대시보드에 그대로 다시 보여도 되도록 실린다 - 링크 시크릿과 달리
 * 이미 손으로 공유될 것을 전제로 만들어진 값이라 시크릿 취급이 아니다.
 */
public record OrganizationTutorInviteSummary(
        UUID id,
        String shortCode,
        Instant expiresAt,
        Instant usedAt,
        UUID usedByTutorId,
        String usedByTutorDisplayName,
        Instant createdAt) {

    public static OrganizationTutorInviteSummary of(OrganizationTutorInvite invite) {
        var used = invite.getUsedByTutor();
        return new OrganizationTutorInviteSummary(
                invite.getId(),
                invite.getShortCode(),
                invite.getExpiresAt(),
                invite.getUsedAt(),
                used == null ? null : used.getId(),
                used == null ? null : used.getDisplayName(),
                invite.getCreatedAt());
    }
}

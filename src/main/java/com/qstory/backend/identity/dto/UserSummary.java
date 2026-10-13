package com.qstory.backend.identity.dto;

import com.qstory.backend.identity.entity.AppUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserSummary(
        UUID id,
        String role,
        String loginId,
        String email,
        String displayName,
        UUID organizationId,
        String subscriptionStatus,
        boolean grantsAccess,
        String childName,
        String profileImageUrl,
        Instant subscriptionExpiresAt,
        /**
         * 아직 받지 않은 필수 동의(GUARDIAN, OVERSEAS_TRANSFER, ORG_GUARDIAN_ACK) - 비어 있지 않으면 프런트가 다시 동의 화면을
         * 먼저 띄운다(약관 2026-10-v2 이전 가입자). ConsentService.pendingConsents.
         */
        List<String> pendingConsents) {

    /** grantsAccess는 개인·기관 구독을 합친 값이라 호출자가 계산해 넘긴다(UserSummaryFactory). */
    public static UserSummary of(AppUser user, boolean grantsAccess, List<String> pendingConsents) {
        return new UserSummary(
                user.getId(), user.getRole().name(), user.getLoginId(), user.getEmail(), user.getDisplayName(),
                user.getOrganization() == null ? null : user.getOrganization().getId(),
                user.getSubscriptionStatus().effectiveAt(user.getSubscriptionExpiresAt(), Instant.now()).name(),
                grantsAccess,
                user.getChildName(),
                user.getProfileImageUrl(),
                user.getSubscriptionExpiresAt(),
                pendingConsents == null ? List.of() : pendingConsents);
    }
}

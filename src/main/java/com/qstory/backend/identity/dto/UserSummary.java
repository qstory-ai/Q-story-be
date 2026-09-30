package com.qstory.backend.identity.dto;

import com.qstory.backend.identity.entity.AppUser;
import java.time.Instant;
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
        Instant subscriptionExpiresAt) {

    /** grantsAccess는 개인·기관 구독을 합친 값이라 호출자가 계산해 넘긴다(UserSummaryFactory). */
    public static UserSummary of(AppUser user, boolean grantsAccess) {
        return new UserSummary(
                user.getId(), user.getRole().name(), user.getLoginId(), user.getEmail(), user.getDisplayName(),
                user.getOrganization() == null ? null : user.getOrganization().getId(),
                user.getSubscriptionStatus().effectiveAt(user.getSubscriptionExpiresAt(), Instant.now()).name(),
                grantsAccess,
                user.getChildName(),
                user.getProfileImageUrl(),
                user.getSubscriptionExpiresAt());
    }
}

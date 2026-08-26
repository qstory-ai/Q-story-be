package com.qstory.backend.identity.dto;

import com.qstory.backend.identity.entity.AppUser;
import java.util.UUID;

public record UserSummary(
        UUID id,
        String role,
        String loginId,
        String displayName,
        UUID organizationId,
        UUID classId,
        String subscriptionStatus,
        boolean grantsAccess,
        String childName) {

    public static UserSummary of(AppUser user) {
        return new UserSummary(
                user.getId(), user.getRole().name(), user.getLoginId(), user.getDisplayName(),
                user.getOrganization() == null ? null : user.getOrganization().getId(),
                user.getClassGroup() == null ? null : user.getClassGroup().getId(),
                user.getSubscriptionStatus().name(),
                user.getSubscriptionStatus().grantsAccess(),
                user.getChildName());
    }
}

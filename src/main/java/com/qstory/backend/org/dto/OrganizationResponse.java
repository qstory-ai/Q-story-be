package com.qstory.backend.org.dto;

import com.qstory.backend.org.entity.Organization;
import java.time.Instant;
import java.util.UUID;

public record OrganizationResponse(UUID id, String name, String subscriptionStatus, Instant createdAt) {

    public static OrganizationResponse of(Organization organization) {
        return new OrganizationResponse(
                organization.getId(), organization.getName(),
                organization.getSubscriptionStatus().name(), organization.getCreatedAt());
    }
}

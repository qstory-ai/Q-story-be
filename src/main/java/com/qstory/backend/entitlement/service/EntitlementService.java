package com.qstory.backend.entitlement.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.OrganizationRepository;
import com.qstory.backend.story.StoryManifest;
import org.springframework.stereotype.Service;

/**
 * Dependency direction is story -> entitlement -> org, never the reverse - the story package
 * doesn't need to know Organization's shape, only "is this caller allowed". "HG" today has
 * requiresEntitlement=false, so every caller (including anonymous) short-circuits before ever
 * touching callerOrNull; this is what keeps the free demo unconditional, not a special case here.
 */
@Service
public class EntitlementService {

    private final OrganizationRepository organizationRepository;

    public EntitlementService(OrganizationRepository organizationRepository) {
        this.organizationRepository = organizationRepository;
    }

    public void assertAccessible(StoryManifest story, CurrentUser callerOrNull) {
        if (!story.requiresEntitlement()) {
            return;
        }
        if (callerOrNull == null || callerOrNull.orgId() == null) {
            throw ApiException.contractError(ErrorCode.ENTITLEMENT_REQUIRED, "이 작품을 이용하려면 유치원 구독이 필요해요.", 402);
        }
        Organization organization = organizationRepository.findById(callerOrNull.orgId()).orElse(null);
        if (organization == null || !organization.getSubscriptionStatus().grantsAccess()) {
            throw ApiException.contractError(ErrorCode.ENTITLEMENT_REQUIRED, "이 작품을 이용하려면 유치원 구독이 필요해요.", 402);
        }
    }
}

package com.qstory.backend.identity.service;

import com.qstory.backend.entitlement.service.EntitlementService;
import com.qstory.backend.identity.dto.UserSummary;
import com.qstory.backend.identity.entity.AppUser;
import org.springframework.stereotype.Component;

/**
 * 응답에 실리는 UserSummary를 만든다 - grantsAccess에 기관 구독(원장·선생님·학생 명단의 학부모)까지 반영하고, 아직 받지 않은
 * 필수 동의(pendingConsents)를 함께 싣는다.
 */
@Component
public class UserSummaryFactory {

    private final EntitlementService entitlementService;
    private final ConsentService consentService;

    public UserSummaryFactory(EntitlementService entitlementService, ConsentService consentService) {
        this.entitlementService = entitlementService;
        this.consentService = consentService;
    }

    public UserSummary of(AppUser user) {
        return UserSummary.of(user, entitlementService.hasAccess(user), consentService.pendingConsents(user));
    }
}

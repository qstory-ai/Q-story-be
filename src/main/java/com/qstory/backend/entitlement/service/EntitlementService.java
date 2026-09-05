package com.qstory.backend.entitlement.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.OrganizationRepository;
import com.qstory.backend.story.StoryManifest;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * 의존 방향은 story -> entitlement -> {org, app_user} 이며 절대 반대 방향이 아니다 - story
 * 패키지는 Organization/AppUser의 형태를 알 필요가 없고, 오직 "이 호출자가 허용되는가"만 알면
 * 된다. 현재 "HG"는 requiresEntitlement=false이므로, 모든 호출자(익명 포함)가 callerOrNull에
 * 손대기도 전에 단축 실행(short-circuit)된다; 무료 데모가 무조건 동작하는 것은 이 덕분이며,
 * 여기 특별한 케이스 처리가 있어서가 아니다.
 *
 * <p>접근권은 기관 구독과 학부모 개인 구독의 OR이다 - 유치원 구독으로 이미 열려 있던 접근을
 * 개인 구독을 추가했다고 잃으면 안 되고, 반대로 유치원 구독이 만료돼도 개인 결제가 있으면
 * 계속 열려 있어야 한다. 두 값 모두 JWT 클레임이 아니라 매 호출마다 DB에서 새로 읽는다 -
 * 구독 상태는 토큰 발급 이후에도 바뀔 수 있어서다(기관 쪽 기존 동작과 동일).
 */
@Service
public class EntitlementService {

    private final OrganizationRepository organizationRepository;
    private final AppUserRepository appUserRepository;

    public EntitlementService(OrganizationRepository organizationRepository, AppUserRepository appUserRepository) {
        this.organizationRepository = organizationRepository;
        this.appUserRepository = appUserRepository;
    }

    public void assertAccessible(StoryManifest story, CurrentUser callerOrNull) {
        if (!story.requiresEntitlement()) {
            return;
        }
        if (callerOrNull == null || !(orgGrantsAccess(callerOrNull) || personalGrantsAccess(callerOrNull))) {
            throw ApiException.contractError(ErrorCode.ENTITLEMENT_REQUIRED, "이 작품을 이용하려면 구독이 필요해요.", 402);
        }
    }

    private boolean orgGrantsAccess(CurrentUser caller) {
        if (caller.orgId() == null) {
            return false;
        }
        Organization organization = organizationRepository.findById(caller.orgId()).orElse(null);
        return organization != null && organization.getSubscriptionStatus()
                .grantsAccessAt(organization.getSubscriptionExpiresAt(), Instant.now());
    }

    private boolean personalGrantsAccess(CurrentUser caller) {
        AppUser user = appUserRepository.findById(caller.userId()).orElse(null);
        return user != null && user.getSubscriptionStatus()
                .grantsAccessAt(user.getSubscriptionExpiresAt(), Instant.now());
    }
}

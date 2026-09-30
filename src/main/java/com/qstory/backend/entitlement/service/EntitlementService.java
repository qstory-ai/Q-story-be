package com.qstory.backend.entitlement.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.OrganizationRepository;
import com.qstory.backend.story.StoryManifest;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * 의존 방향은 story -> entitlement -> {org, app_user} 이며 절대 반대 방향이 아니다 - story
 * 패키지는 Organization/AppUser의 형태를 알 필요가 없고, 오직 "이 호출자가 허용되는가"만 알면
 * 된다. 현재 "HG"는 requiresEntitlement=false이므로, 모든 호출자(익명 포함)가 callerOrNull에
 * 손대기도 전에 단축 실행(short-circuit)된다; 무료 데모가 무조건 동작하는 것은 이 덕분이며,
 * 여기 특별한 케이스 처리가 있어서가 아니다.
 *
 * <p>접근권은 기관 구독과 학부모 개인 구독의 OR이다. 학부모에게 기관 구독이 적용되는 근거는 계정의
 * 소속이 아니라 학생 명단이다 - 이 부모의 아이가 들어가 있는 기관 반의 기관 중 하나라도 구독이
 * 유효하면 열린다. 부모가 아이를 반에서 빼거나 학생이 명단에서 지워지면 그 기관의 권한은 사라진다.
 * 모든 값을 JWT 클레임이 아니라 매 호출마다 DB에서 새로 읽는다 - 구독 상태와 명단은 토큰 발급 이후에도
 * 바뀔 수 있어서다.
 */
@Service
public class EntitlementService {

    private final OrganizationRepository organizationRepository;
    private final AppUserRepository appUserRepository;
    private final TutorStudentRepository tutorStudentRepository;

    public EntitlementService(
            OrganizationRepository organizationRepository, AppUserRepository appUserRepository,
            TutorStudentRepository tutorStudentRepository) {
        this.organizationRepository = organizationRepository;
        this.appUserRepository = appUserRepository;
        this.tutorStudentRepository = tutorStudentRepository;
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
        if (caller.role() == Role.DIRECTOR && caller.orgId() != null) {
            return organizationRepository.findById(caller.orgId()).filter(this::grantsAccess).isPresent();
        }
        if (caller.role() == Role.PARENT) {
            return tutorStudentRepository.findOrganizationsOfParent(caller.userId()).stream().anyMatch(this::grantsAccess);
        }
        return false;
    }

    private boolean grantsAccess(Organization organization) {
        return organization.getSubscriptionStatus().grantsAccessAt(organization.getSubscriptionExpiresAt(), Instant.now());
    }

    private boolean personalGrantsAccess(CurrentUser caller) {
        AppUser user = appUserRepository.findById(caller.userId()).orElse(null);
        return user != null && user.getSubscriptionStatus()
                .grantsAccessAt(user.getSubscriptionExpiresAt(), Instant.now());
    }
}

package com.qstory.backend.entitlement.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.config.BetaProperties;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.OrganizationRepository;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.story.StoryManifest;
import com.qstory.backend.tutor.repository.ParentClassSeat;
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
 * <p>접근권은 기관 구독과 개인 구독의 OR이다. 기관 구독이 적용되는 사람은 셋이다:
 * 원장(자기 기관), 선생님(소속 기관 중 하나라도 구독이 유효하면), 학부모(아이가 들어간 기관 반의 기관
 * 구독이 유효하고 아이가 결제한 인원 안에 들 때). 학부모의 근거는 계정 소속이 아니라 학생 명단이고, 결제
 * 인원(subscription_seats)은 학부모가 연결된 학생 중 먼저 등록된 학생부터 채운다 - 인원 기록이 없는 예전 구독은 제한이
 * 없다. 모든 값을 JWT 클레임이 아니라 매 호출마다 DB에서 새로 읽는다 - 구독 상태와 명단은 토큰 발급
 * 이후에도 바뀔 수 있어서다.
 */
@Service
public class EntitlementService {

    private final OrganizationRepository organizationRepository;
    private final AppUserRepository appUserRepository;
    private final TutorStudentRepository tutorStudentRepository;
    private final OrganizationTutorRepository organizationTutorRepository;
    private final BetaProperties beta;

    public EntitlementService(
            OrganizationRepository organizationRepository, AppUserRepository appUserRepository,
            TutorStudentRepository tutorStudentRepository, OrganizationTutorRepository organizationTutorRepository,
            BetaProperties beta) {
        this.organizationRepository = organizationRepository;
        this.appUserRepository = appUserRepository;
        this.tutorStudentRepository = tutorStudentRepository;
        this.organizationTutorRepository = organizationTutorRepository;
        this.beta = beta;
    }

    public void assertAccessible(StoryManifest story, CurrentUser callerOrNull) {
        if (!story.requiresEntitlement()) {
            return;
        }
        if (callerOrNull == null || !(betaOpen(callerOrNull.role()) || orgGrantsAccess(callerOrNull) || personalGrantsAccess(callerOrNull))) {
            throw ApiException.contractError(ErrorCode.ENTITLEMENT_REQUIRED, "이 이야기는 이용권이 있어야 열려요.", 402);
        }
    }

    /** 화면이 잠금을 판단하는 "지금 전체 이야기를 이용할 수 있는가" - 개인 구독과 기관 구독을 합친 값이다. */
    public boolean hasAccess(AppUser user) {
        if (betaOpen(user.getRole())) {
            return true;
        }
        boolean personal = user.getSubscriptionStatus().grantsAccessAt(user.getSubscriptionExpiresAt(), Instant.now());
        return personal || orgGrantsAccess(new CurrentUser(
                user.getId(), user.getRole(), user.getOrganization() == null ? null : user.getOrganization().getId()));
    }

    /** 베타 기간 선생님·관리자 전체 개방 - 보호자는 해당하지 않는다. */
    private boolean betaOpen(Role role) {
        return beta.openAccessTutorOrg() && (role == Role.TUTOR || role == Role.DIRECTOR);
    }

    private boolean orgGrantsAccess(CurrentUser caller) {
        return switch (caller.role()) {
            case DIRECTOR -> caller.orgId() != null
                    && organizationRepository.findById(caller.orgId()).filter(this::grantsAccess).isPresent();
            case TUTOR -> organizationTutorRepository.findOrganizationsOfTutor(caller.userId()).stream()
                    .anyMatch(this::grantsAccess);
            case PARENT -> tutorStudentRepository.findClassSeatsOfParent(caller.userId()).stream()
                    .anyMatch(seat -> grantsAccess(seat.organization()) && withinPaidSeats(seat));
            default -> false;
        };
    }

    /** 결제한 인원 안에 드는가 - 학부모가 연결된 학생 중 먼저 등록된 학생부터 채운다. 인원 기록이 없으면 제한이 없다. */
    private boolean withinPaidSeats(ParentClassSeat seat) {
        Integer seats = seat.organization().getSubscriptionSeats();
        if (seats == null) {
            return true;
        }
        return tutorStudentRepository.countEarlierInOrganization(
                seat.organization().getId(), seat.linkedAt(), seat.studentId()) < seats;
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

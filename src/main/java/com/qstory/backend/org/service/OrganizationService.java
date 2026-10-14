package com.qstory.backend.org.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.JwtService;
import com.qstory.backend.identity.service.ConsentService;
import com.qstory.backend.identity.service.UserSummaryFactory;
import com.qstory.backend.institution.entity.PublicInstitution;
import com.qstory.backend.institution.repository.PublicInstitutionRepository;
import com.qstory.backend.org.SubscriptionStatus;
import com.qstory.backend.org.dto.CreateOrganizationRequest;
import com.qstory.backend.org.dto.EntitlementResponse;
import com.qstory.backend.org.dto.OrganizationProfileResponse;
import com.qstory.backend.org.dto.GuardianConsentAckRequest;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.OrganizationRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final AppUserRepository userRepository;
    private final JwtService jwtService;
    private final UserSummaryFactory userSummaryFactory;
    private final PublicInstitutionRepository publicInstitutionRepository;
    private final ConsentService consentService;

    public OrganizationService(
            OrganizationRepository organizationRepository, AppUserRepository userRepository,
            JwtService jwtService, UserSummaryFactory userSummaryFactory,
            PublicInstitutionRepository publicInstitutionRepository, ConsentService consentService) {
        this.consentService = consentService;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.userSummaryFactory = userSummaryFactory;
        this.publicInstitutionRepository = publicInstitutionRepository;
    }

    /**
     * 갱신된 AuthResponse를 반환한다 - 호출자의 기존 JWT는 기관 생성 전에 발급돼 orgId 클레임이 null이다.
     * 이후의 org/class 엔드포인트는 그 클레임으로 권한을 검사하므로(requireOwned 참고), 클라이언트가 이 새
     * 토큰으로 교체하지 않으면 이후 호출이 403을 반환한다.
     */
    @Transactional
    public AuthResponse create(CurrentUser caller, CreateOrganizationRequest request) {
        AppUser director = userRepository.findById(caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.UNAUTHENTICATED, "로그인이 필요해요.", 401));
        if (director.getOrganization() != null) {
            throw ApiException.contractError(ErrorCode.ORGANIZATION_ALREADY_EXISTS, "이미 등록된 유치원이 있어요.");
        }
        // 보호자 동의 확인(A5)은 목록에서 고른 등록이든 직접 입력이든 먼저 본다.
        String version = requireGuardianAck(request.guardianConsentAck(), request.consentVersion());
        PublicInstitution institution = request.publicInstitutionId() == null
                ? null
                : requireRegistrable(request.publicInstitutionId());
        String name = institution != null ? institution.getName() : request.name();
        if (name == null || name.isBlank()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "유치원 이름을 입력해 주세요.");
        }
        Instant now = Instant.now();
        Organization organization = saveUnique(Organization.builder()
                .name(name.trim())
                .publicInstitutionId(institution == null ? null : institution.getId())
                .guardianConsentAckAt(now)
                .guardianConsentAckVersion(version)
                .guardianConsentAckBy(director.getId())
                .createdAt(now)
                .build());
        consentService.recordOrganizationGuardianAck(director.getId(), version, "ORGANIZATION_CREATE");
        director.setOrganization(organization);
        director = userRepository.save(director);
        CurrentUser refreshed = new CurrentUser(
                director.getId(), director.getRole(), organization.getId(), caller.rememberMe(),
                director.getTokenVersion(), caller.authTime());
        return new AuthResponse(jwtService.issue(refreshed), userSummaryFactory.of(director));
    }

    /** 원장 화면 - 우리 기관이 공공 기관 목록의 어느 곳과 연결됐는지(선생님이 검색으로 찾을 수 있는지). */
    @Transactional(readOnly = true)
    public OrganizationProfileResponse profile(CurrentUser caller, UUID organizationId) {
        Organization organization = requireOwned(caller, organizationId);
        PublicInstitution institution = organization.getPublicInstitutionId() == null
                ? null
                : publicInstitutionRepository.findById(organization.getPublicInstitutionId()).orElse(null);
        return OrganizationProfileResponse.of(organization, institution);
    }

    /**
     * 이미 직접 입력으로 등록한 기관을 공공 기관 목록의 한 곳과 연결한다 - 그래야 선생님이 검색해 소속 신청할 수 있다.
     * 기관 이름은 바꾸지 않는다(원장이 정한 이름). 다른 기관이 이미 연결한 곳이면 409. 같은 곳으로 다시 부르면 그대로 둔다.
     */
    @Transactional
    public OrganizationProfileResponse linkPublicInstitution(
            CurrentUser caller, UUID organizationId, UUID publicInstitutionId) {
        Organization organization = requireOwned(caller, organizationId);
        if (publicInstitutionId == null) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "연결할 기관을 골라 주세요.");
        }
        PublicInstitution institution;
        if (publicInstitutionId.equals(organization.getPublicInstitutionId())) {
            institution = publicInstitutionRepository.findById(publicInstitutionId).orElse(null);
        } else {
            institution = requireRegistrable(publicInstitutionId);
            organization.setPublicInstitutionId(institution.getId());
            organization = saveUnique(organization);
        }
        return OrganizationProfileResponse.of(organization, institution);
    }

    private PublicInstitution requireRegistrable(UUID publicInstitutionId) {
        PublicInstitution institution = publicInstitutionRepository.findById(publicInstitutionId)
                .filter(PublicInstitution::isActive)
                .orElseThrow(() -> ApiException.contractError(
                        ErrorCode.NOT_FOUND, "고른 기관을 찾을 수 없어요. 다시 검색해 주세요.", 404));
        if (organizationRepository.existsByPublicInstitutionId(institution.getId())) {
            throw alreadyRegistered();
        }
        return institution;
    }

    /** 두 원장이 같은 곳을 동시에 고르면 유니크 색인(083)이 하나를 막는다 - 그 경우도 같은 409로 안내한다. */
    private Organization saveUnique(Organization organization) {
        try {
            return organizationRepository.saveAndFlush(organization);
        } catch (DataIntegrityViolationException e) {
            throw alreadyRegistered();
        }
    }

    private static ApiException alreadyRegistered() {
        return ApiException.contractError(ErrorCode.PUBLIC_INSTITUTION_ALREADY_REGISTERED,
                "이미 다른 관리자가 등록한 기관이에요. 그 기관의 관리자에게 선생님 초대를 받거나, 잘못 등록됐다면 고객센터로 알려 주세요.");
    }

    /** 이미 있는 기관(약관 2026-10-v2 이전에 만든 기관)의 보호자 동의 확인 - 다시 동의 화면에서 부른다. */
    @Transactional
    public void acknowledgeGuardianConsent(CurrentUser caller, UUID organizationId, GuardianConsentAckRequest request) {
        Organization organization = requireOwned(caller, organizationId);
        String version = requireGuardianAck(true, request == null ? null : request.consentVersion());
        organization.setGuardianConsentAckAt(Instant.now());
        organization.setGuardianConsentAckVersion(version);
        organization.setGuardianConsentAckBy(caller.userId());
        organizationRepository.save(organization);
        consentService.recordOrganizationGuardianAck(caller.userId(), version, "RECONSENT");
    }

    /** 확인이 없거나 버전이 없으면 400 CONSENT_REQUIRED - 예전 화면(확인 항목 없음)에서 온 기관 등록도 여기서 막힌다. */
    private static String requireGuardianAck(Boolean acknowledged, String version) {
        if (!Boolean.TRUE.equals(acknowledged) || version == null || version.isBlank() || version.length() > 40) {
            throw ApiException.contractError(ErrorCode.CONSENT_REQUIRED,
                    "원아의 보호자 동의를 받아 이용한다는 확인이 필요해요. 앱을 업데이트하거나 화면을 새로 고친 뒤 다시 시도해 주세요.");
        }
        return version.trim();
    }

    public EntitlementResponse entitlement(CurrentUser caller, UUID organizationId) {
        Organization organization = requireOwned(caller, organizationId);
        SubscriptionStatus status = organization.getSubscriptionStatus();
        Instant expiresAt = organization.getSubscriptionExpiresAt();
        Instant now = Instant.now();
        return new EntitlementResponse(
                status.effectiveAt(expiresAt, now).name(), status.grantsAccessAt(expiresAt, now), expiresAt);
    }

    /** ClassService가 반을 생성/조회할 때 동일한 소유권 검사를 재사용할 수 있도록 패키지 가시성으로 둔다. */
    Organization requireOwned(CurrentUser caller, UUID organizationId) {
        if (!organizationId.equals(caller.orgId())) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "이 유치원에 접근할 권한이 없어요.", 403);
        }
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "유치원을 찾을 수 없어요.", 404));
    }
}

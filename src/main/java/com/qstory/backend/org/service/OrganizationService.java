package com.qstory.backend.org.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.dto.AuthResponse;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.JwtService;
import com.qstory.backend.identity.service.UserSummaryFactory;
import com.qstory.backend.org.SubscriptionStatus;
import com.qstory.backend.org.dto.CreateOrganizationRequest;
import com.qstory.backend.org.dto.EntitlementResponse;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.OrganizationRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final AppUserRepository userRepository;
    private final JwtService jwtService;
    private final UserSummaryFactory userSummaryFactory;

    public OrganizationService(
            OrganizationRepository organizationRepository, AppUserRepository userRepository,
            JwtService jwtService, UserSummaryFactory userSummaryFactory) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.userSummaryFactory = userSummaryFactory;
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
        if (request.name() == null || request.name().isBlank()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "유치원 이름을 입력해 주세요.");
        }
        Organization organization = organizationRepository.save(Organization.builder()
                .name(request.name().trim())
                .createdAt(Instant.now())
                .build());
        director.setOrganization(organization);
        director = userRepository.save(director);
        CurrentUser refreshed = new CurrentUser(
                director.getId(), director.getRole(), organization.getId(), caller.rememberMe());
        return new AuthResponse(jwtService.issue(refreshed), userSummaryFactory.of(director));
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

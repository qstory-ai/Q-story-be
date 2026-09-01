package com.qstory.backend.org.tutor.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.DigestUtil;
import com.qstory.backend.common.util.SecureTokenGenerator;
import com.qstory.backend.common.util.TokenValidation;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.OrganizationRepository;
import com.qstory.backend.org.tutor.dto.OrganizationTutorInvitePreviewResponse;
import com.qstory.backend.org.tutor.dto.OrganizationTutorInviteResponse;
import com.qstory.backend.org.tutor.dto.OrganizationTutorInviteSummary;
import com.qstory.backend.org.tutor.dto.OrganizationTutorResponse;
import com.qstory.backend.org.tutor.dto.TutorOrganizationResponse;
import com.qstory.backend.org.tutor.entity.OrganizationTutor;
import com.qstory.backend.org.tutor.entity.OrganizationTutorInvite;
import com.qstory.backend.org.tutor.repository.OrganizationTutorInviteRepository;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.org.util.JoinCodeGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기관(DIRECTOR)이 소속 선생님(TUTOR)을 초대·관리하는 CRUD. TutorStudentService의 부모 초대와
 * 완전히 같은 규약: 원본 token은 발급 시 한 번만 반환되고 sha-256만 저장, 손으로 옮길 수 있는
 * short_code(8자)를 함께 발급, 만료 14일, 1회용, 사용 시 used_at/used_by_tutor 기록.
 *
 * <p>소유권 검증은 CurrentUser.orgId()로만 한다 - JWT 클레임에 이미 담겨 있어서 DB를 다시 치지
 * 않고도 접근 통제가 가능. 다른 기관에 접근하려는 시도는 403.
 */
@Service
public class OrganizationTutorService {

    private static final Duration INVITE_TTL = Duration.ofDays(14);

    private final OrganizationTutorRepository organizationTutorRepository;
    private final OrganizationTutorInviteRepository organizationTutorInviteRepository;
    private final OrganizationRepository organizationRepository;
    private final AppUserRepository userRepository;
    private final SecureTokenGenerator tokenGenerator;
    private final JoinCodeGenerator joinCodeGenerator;

    public OrganizationTutorService(
            OrganizationTutorRepository organizationTutorRepository,
            OrganizationTutorInviteRepository organizationTutorInviteRepository,
            OrganizationRepository organizationRepository,
            AppUserRepository userRepository,
            SecureTokenGenerator tokenGenerator,
            JoinCodeGenerator joinCodeGenerator) {
        this.organizationTutorRepository = organizationTutorRepository;
        this.organizationTutorInviteRepository = organizationTutorInviteRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.tokenGenerator = tokenGenerator;
        this.joinCodeGenerator = joinCodeGenerator;
    }

    /* ---------------------------------------------------------- listings */

    @Transactional(readOnly = true)
    public List<OrganizationTutorResponse> listOrganizationTutors(CurrentUser caller, UUID organizationId) {
        requireOwnedByCaller(caller, organizationId);
        return organizationTutorRepository.findByOrganization_IdOrderByJoinedAtAsc(organizationId).stream()
                .map(OrganizationTutorResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrganizationTutorInviteSummary> listInvites(CurrentUser caller, UUID organizationId) {
        requireOwnedByCaller(caller, organizationId);
        return organizationTutorInviteRepository
                .findByOrganization_IdOrderByCreatedAtDesc(organizationId).stream()
                .map(OrganizationTutorInviteSummary::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TutorOrganizationResponse> listMyOrganizations(CurrentUser caller) {
        return organizationTutorRepository.findByTutor_IdOrderByJoinedAtAsc(caller.userId()).stream()
                .map(TutorOrganizationResponse::of)
                .toList();
    }

    /* ---------------------------------------------------------- issue invite */

    @Transactional
    public OrganizationTutorInviteResponse createInvite(CurrentUser caller, UUID organizationId) {
        Organization organization = requireOwnedByCaller(caller, organizationId);
        String rawToken = tokenGenerator.generate();
        String shortCode = generateUniqueShortCode();
        Instant expiresAt = Instant.now().plus(INVITE_TTL);
        OrganizationTutorInvite saved = organizationTutorInviteRepository.save(OrganizationTutorInvite.builder()
                .organization(organization)
                .tokenHash(DigestUtil.sha256Hex(rawToken))
                .shortCode(shortCode)
                .expiresAt(expiresAt)
                .createdAt(Instant.now())
                .build());
        return new OrganizationTutorInviteResponse(saved.getId(), rawToken, shortCode, expiresAt);
    }

    /* ---------------------------------------------------------- preview + accept (public/authenticated) */

    @Transactional(readOnly = true)
    public OrganizationTutorInvitePreviewResponse previewInvite(String rawToken) {
        return previewOf(requireInviteByToken(rawToken));
    }

    @Transactional(readOnly = true)
    public OrganizationTutorInvitePreviewResponse previewInviteByCode(String shortCode) {
        return previewOf(requireInviteByShortCode(shortCode));
    }

    private static OrganizationTutorInvitePreviewResponse previewOf(OrganizationTutorInvite invite) {
        return new OrganizationTutorInvitePreviewResponse(invite.getOrganization().getName());
    }

    /**
     * 이미 로그인된 TUTOR만 수락할 수 있다 - PARENT 초대(TutorStudentService.acceptInvite)와 달리
     * 여기는 "새 계정을 만들며 함께 수락"하는 흐름을 지원하지 않는다. 기관 소속은 이미 TUTOR로
     * 활동 중인 선생님이 자기 계정에 붙이는 일이라, 새 회원가입 흐름과 섞으면 계정 종류/역할
     * 판단이 복잡해져서다.
     */
    @Transactional
    public OrganizationTutorResponse acceptInvite(CurrentUser caller, String rawToken) {
        return consumeInvite(caller, requireInviteByToken(rawToken));
    }

    @Transactional
    public OrganizationTutorResponse acceptInviteByCode(CurrentUser caller, String shortCode) {
        return consumeInvite(caller, requireInviteByShortCode(shortCode));
    }

    private OrganizationTutorResponse consumeInvite(CurrentUser caller, OrganizationTutorInvite invite) {
        if (caller.role() != Role.TUTOR) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "선생님 계정만 기관 초대를 수락할 수 있어요.", 403);
        }
        AppUser tutor = userRepository.findById(caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.UNAUTHENTICATED, "로그인이 필요해요.", 401));
        Organization organization = invite.getOrganization();

        // 이미 소속이면 초대만 사용 처리하고 기존 관계를 반환 - idempotent.
        OrganizationTutor link = organizationTutorRepository
                .findByOrganization_IdAndTutor_Id(organization.getId(), tutor.getId())
                .orElseGet(() -> organizationTutorRepository.save(OrganizationTutor.builder()
                        .organization(organization)
                        .tutor(tutor)
                        .joinedAt(Instant.now())
                        .build()));

        invite.setUsedAt(Instant.now());
        invite.setUsedByTutor(tutor);
        organizationTutorInviteRepository.save(invite);

        return OrganizationTutorResponse.of(link);
    }

    /* ---------------------------------------------------------- unlink */

    @Transactional
    public void unlinkTutor(CurrentUser caller, UUID organizationId, UUID tutorId) {
        requireOwnedByCaller(caller, organizationId);
        organizationTutorRepository.findByOrganization_IdAndTutor_Id(organizationId, tutorId)
                .ifPresent(organizationTutorRepository::delete);
    }

    /* ---------------------------------------------------------- helpers */

    private Organization requireOwnedByCaller(CurrentUser caller, UUID organizationId) {
        if (caller.orgId() == null || !caller.orgId().equals(organizationId)) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "이 기관에 접근할 권한이 없어요.", 403);
        }
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "기관을 찾을 수 없어요.", 404));
    }

    private OrganizationTutorInvite requireInviteByToken(String rawToken) {
        OrganizationTutorInvite invite = organizationTutorInviteRepository
                .findByTokenHash(DigestUtil.sha256Hex(rawToken))
                .orElseThrow(() -> ApiException.contractError(ErrorCode.INVALID_INVITE, "초대 링크가 올바르지 않아요.", 410));
        TokenValidation.requireUsable(invite.getUsedAt(), invite.getExpiresAt(),
                ErrorCode.INVALID_INVITE, "만료되었거나 이미 사용된 초대 링크예요.", 410);
        return invite;
    }

    private OrganizationTutorInvite requireInviteByShortCode(String shortCode) {
        String normalized = shortCode == null ? "" : shortCode.trim().toUpperCase();
        if (normalized.isEmpty()) {
            throw ApiException.contractError(ErrorCode.INVALID_INVITE, "초대 코드가 올바르지 않아요.", 410);
        }
        OrganizationTutorInvite invite = organizationTutorInviteRepository.findByShortCode(normalized)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.INVALID_INVITE, "초대 코드가 올바르지 않아요.", 410));
        TokenValidation.requireUsable(invite.getUsedAt(), invite.getExpiresAt(),
                ErrorCode.INVALID_INVITE, "만료되었거나 이미 사용된 초대 코드예요.", 410);
        return invite;
    }

    private String generateUniqueShortCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = joinCodeGenerator.generate();
            if (!organizationTutorInviteRepository.existsByShortCode(code)) return code;
        }
        throw ApiException.contractError(ErrorCode.INTERNAL_ERROR, "초대 코드를 만들지 못했어요. 잠시 후 다시 시도해 주세요.");
    }
}

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
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.ClassGroupRepository;
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
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.org.service.ClassHomeroomHistoryService;
import com.qstory.backend.tutor.lesson.LessonStatus;
import com.qstory.backend.tutor.lesson.entity.Lesson;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기관(DIRECTOR)이 소속 선생님(TUTOR)을 초대·관리하는 CRUD. TutorStudentService의 부모 초대와
 * 같은 규약: 원본 token은 발급 시 한 번만 반환되고 sha-256만 저장, 손으로 옮길 수 있는
 * short_code(8자)를 함께 발급, 만료 14일, 1회용, 사용 시 used_at/used_by_tutor 기록.
 *
 * <p>소유권 검증은 JWT의 CurrentUser.orgId()로 한다 - 다른 기관에 접근하려는 시도는 403.
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
    private final NotificationPublisher notificationPublisher;
    private final ClassGroupRepository classGroupRepository;
    private final TutorStudentRepository tutorStudentRepository;
    private final LessonRepository lessonRepository;
    private final ClassHomeroomHistoryService homeroomHistoryService;

    public OrganizationTutorService(
            OrganizationTutorRepository organizationTutorRepository,
            OrganizationTutorInviteRepository organizationTutorInviteRepository,
            OrganizationRepository organizationRepository,
            AppUserRepository userRepository,
            SecureTokenGenerator tokenGenerator,
            JoinCodeGenerator joinCodeGenerator,
            NotificationPublisher notificationPublisher,
            ClassGroupRepository classGroupRepository,
            TutorStudentRepository tutorStudentRepository,
            LessonRepository lessonRepository,
            ClassHomeroomHistoryService homeroomHistoryService) {
        this.organizationTutorRepository = organizationTutorRepository;
        this.organizationTutorInviteRepository = organizationTutorInviteRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.tokenGenerator = tokenGenerator;
        this.joinCodeGenerator = joinCodeGenerator;
        this.notificationPublisher = notificationPublisher;
        this.classGroupRepository = classGroupRepository;
        this.tutorStudentRepository = tutorStudentRepository;
        this.lessonRepository = lessonRepository;
        this.homeroomHistoryService = homeroomHistoryService;
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
        Instant now = Instant.now();
        Instant expiresAt = now.plus(INVITE_TTL);
        OrganizationTutorInvite saved = organizationTutorInviteRepository.save(OrganizationTutorInvite.builder()
                .organization(organization)
                .tokenHash(DigestUtil.sha256Hex(rawToken))
                .shortCode(shortCode)
                .expiresAt(expiresAt)
                .createdAt(now)
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
        OrganizationTutor link = linkTutor(invite.getOrganization(), tutor, "org-tutor-invite-accepted:" + invite.getId());

        invite.setUsedAt(Instant.now());
        invite.setUsedByTutor(tutor);
        organizationTutorInviteRepository.save(invite);
        return OrganizationTutorResponse.of(link);
    }

    /**
     * 선생님을 기관에 소속시킨다 - 기관 선생님 초대 수락과 반 담임 초대 수락(ClassHomeroomInviteService)이 같이 쓴다.
     * 이미 소속이면 기존 관계를 그대로 돌려준다(idempotent). 다른 기관에 이미 소속된 선생님도 막지 않는다 - 스키마가
     * 여러 기관 소속을 허용한다(organization_tutor는 (organization_id, tutor_id)만 유니크).
     *
     * <p>새 소속이 실제로 생긴 경우에만 원장에게 알림 - 이미 소속됐던 튜터가 초대를 재사용 시도한(=idempotent)
     * 경우엔 원장에게 스팸을 보내지 않는다. Organization은 owning director를 FK로 가지지 않으므로 role=DIRECTOR인
     * 소속 사용자를 조회한다. notificationKey는 같은 알림이 두 번 가지 않게 하는 중복 방지 키다.
     */
    @Transactional
    public OrganizationTutor linkTutor(Organization organization, AppUser tutor, String notificationKey) {
        Optional<OrganizationTutor> existing =
                organizationTutorRepository.findByOrganization_IdAndTutor_Id(organization.getId(), tutor.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        OrganizationTutor link = organizationTutorRepository.save(OrganizationTutor.builder()
                .organization(organization)
                .tutor(tutor)
                .joinedAt(Instant.now())
                .build());
        userRepository
                .findFirstByOrganization_IdAndRoleAndDeletedAtIsNull(organization.getId(), Role.DIRECTOR)
                .ifPresent(director -> notificationPublisher.publish(
                        director.getId(),
                        "org-tutor-invite-accepted",
                        tutor.getDisplayName() + " 선생님이 소속을 수락했어요",
                        organization.getName() + " 소속 선생님 목록에 추가됐어요.",
                        "/organization/tutors",
                        notificationKey));
        return link;
    }

    /* ---------------------------------------------------------- unlink */

    /**
     * 소속 해제. 링크 행과 함께 기관 안의 흔적도 정리한다 - 이 선생님이 담임인 기관 반은 담임 미정(tutor_id null)으로,
     * 그 기관 반에 들어 있던 이 선생님의 학생은 개인 레슨으로, 그 반에 묶인 예정 수업은 반 없는 수업으로 되돌린다.
     * 이미 끝난 수업과 완주 기록은 건드리지 않는다.
     */
    @Transactional
    public void unlinkTutor(CurrentUser caller, UUID organizationId, UUID tutorId) {
        requireOwnedByCaller(caller, organizationId);
        organizationTutorRepository.findByOrganization_IdAndTutor_Id(organizationId, tutorId).ifPresent(link -> {
            detachTutor(link.getOrganization().getId(), tutorId);
            organizationTutorRepository.delete(link);
        });
    }

    /**
     * 선생님이 기관을 떠날 때(원장이 내보내거나 선생님이 탈퇴할 때) 기관 반을 정리한다. 기관 반의 명단은 기관 것이라
     * 학생은 반에 그대로 두고 담임만 비운다(대기 명단) - 학부모의 기관 이용권이 유지되고, 원장이 새 담임을 배정하면
     * 그대로 넘어간다. 떠나는 선생님의 예정 수업은 반에서 떼어 낸다.
     */
    @Transactional
    public void detachTutor(UUID organizationId, UUID tutorId) {
        Instant now = Instant.now();
        for (TutorStudent student : tutorStudentRepository
                .findByTutor_IdAndClassGroup_Organization_IdAndDeletedAtIsNull(tutorId, organizationId)) {
            student.setTutor(null);
            tutorStudentRepository.save(student);
        }
        for (Lesson lesson : lessonRepository
                .findByTutor_IdAndClassGroup_Organization_IdAndStatus(tutorId, organizationId, LessonStatus.SCHEDULED)) {
            lesson.setClassGroup(null);
            lesson.setUpdatedAt(now);
            lessonRepository.save(lesson);
        }
        for (ClassGroup classGroup : classGroupRepository.findByOrganization_IdAndTutor_Id(organizationId, tutorId)) {
            classGroup.setTutor(null);
            classGroupRepository.save(classGroup);
            homeroomHistoryService.end(classGroup, now);
        }
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
        return joinCodeGenerator.generateUnique(organizationTutorInviteRepository::existsByShortCode,
                () -> ApiException.contractError(ErrorCode.INTERNAL_ERROR, "초대 코드를 만들지 못했어요. 잠시 후 다시 시도해 주세요."));
    }
}

package com.qstory.backend.org.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.SecureTokenGenerator;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.org.dto.ClassHomeroomInvitePreviewResponse;
import com.qstory.backend.org.dto.ClassHomeroomInviteResponse;
import com.qstory.backend.org.dto.ClassResponse;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.ClassHomeroomInvite;
import com.qstory.backend.org.repository.ClassHomeroomInviteRepository;
import com.qstory.backend.org.tutor.service.OrganizationTutorService;
import com.qstory.backend.org.util.JoinCodeGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반 담임 초대(db/schema/074). 반마다 초대가 두 가지다 - 반 초대(학부모, 반 코드)와 담임 초대(선생님, 이 서비스).
 * 원장이 반에서 담임 초대를 발급하고, 선생님이 그 코드로 가입/로그인해 수락하면 기관에 소속되고 그 반의 담임이 된다.
 *
 * <p>기관 선생님 초대(OrganizationTutorService)와 같은 규약: 8자 short_code(JoinCodeGenerator), 만료 14일, 1회용.
 * 기관 소속은 기관 초대 수락과 같은 경로(OrganizationTutorService.linkTutor)로, 담임 배정은 원장의 배정과 같은
 * 경로(ClassService.applyHomeroom)로 한다 - 담임 이력도 같이 남는다.
 */
@Service
public class ClassHomeroomInviteService {

    private static final Duration INVITE_TTL = Duration.ofDays(14);
    static final String USED_DETAIL = "이미 사용한 초대 코드예요.";
    static final String REPLACED_DETAIL = "새 코드로 바뀌어서 이 초대 코드는 쓸 수 없어요. 원장 선생님께 새 링크를 받아 주세요.";
    static final String EXPIRED_DETAIL = "초대 코드 기한이 지났어요. 원장 선생님께 새 링크를 받아 주세요.";

    private final ClassHomeroomInviteRepository inviteRepository;
    private final ClassService classService;
    private final OrganizationTutorService organizationTutorService;
    private final AppUserRepository userRepository;
    private final SecureTokenGenerator tokenGenerator;
    private final JoinCodeGenerator joinCodeGenerator;
    private final NotificationPublisher notificationPublisher;

    public ClassHomeroomInviteService(
            ClassHomeroomInviteRepository inviteRepository, ClassService classService,
            OrganizationTutorService organizationTutorService, AppUserRepository userRepository,
            SecureTokenGenerator tokenGenerator, JoinCodeGenerator joinCodeGenerator,
            NotificationPublisher notificationPublisher) {
        this.notificationPublisher = notificationPublisher;
        this.inviteRepository = inviteRepository;
        this.classService = classService;
        this.organizationTutorService = organizationTutorService;
        this.userRepository = userRepository;
        this.tokenGenerator = tokenGenerator;
        this.joinCodeGenerator = joinCodeGenerator;
    }

    /**
     * 담임 초대를 새로 발급한다 - 원장만. 살아 있던 이전 초대는 "새 코드로 바뀜"(revokedAt)이 되어 더 이상 쓸 수 없고,
     * 그 코드로 들어온 선생님에게는 410과 함께 새 링크를 받으라는 안내가 간다. 원장이 한 일이라 알림은 없다.
     */
    @Transactional
    public ClassHomeroomInviteResponse issue(CurrentUser caller, UUID classId) {
        ClassGroup classGroup = classService.requireOwnedByDirector(caller, classId);
        Instant now = Instant.now();
        inviteRepository.revokeLiveByClassGroupId(classGroup.getId(), now);
        ClassHomeroomInvite saved = inviteRepository.save(ClassHomeroomInvite.builder()
                .classGroup(classGroup)
                .token(tokenGenerator.generate())
                .shortCode(generateUniqueShortCode())
                .createdBy(userRepository.getReferenceById(caller.userId()))
                .createdAt(now)
                .expiresAt(now.plus(INVITE_TTL))
                .build());
        return ClassHomeroomInviteResponse.of(saved);
    }

    /** 지금 쓸 수 있는 담임 초대 - 원장만. 없으면(발급 전, 만료, 사용됨) 404. */
    @Transactional(readOnly = true)
    public ClassHomeroomInviteResponse current(CurrentUser caller, UUID classId) {
        ClassGroup classGroup = classService.requireOwnedByDirector(caller, classId);
        return inviteRepository
                .findFirstByClassGroup_IdAndUsedAtIsNullAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                        classGroup.getId(), Instant.now())
                .map(ClassHomeroomInviteResponse::of)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "지금 쓸 수 있는 담임 초대가 없어요.", 404));
    }

    /** 로그인 없이 코드로 미리보기. 없는 코드는 404, 만료되었거나 이미 쓴 코드는 410. */
    @Transactional(readOnly = true)
    public ClassHomeroomInvitePreviewResponse preview(String code) {
        ClassHomeroomInvite invite = requireUsable(inviteRepository.findByShortCode(normalize(code)).orElse(null));
        ClassGroup classGroup = invite.getClassGroup();
        AppUser homeroom = classGroup.getTutor();
        return new ClassHomeroomInvitePreviewResponse(
                classGroup.getOrganization() == null ? null : classGroup.getOrganization().getName(),
                classGroup.getName(), invite.getExpiresAt(),
                homeroom == null || homeroom.getDeletedAt() != null ? null : homeroom.getDisplayName());
    }

    /**
     * 선생님이 담임 초대를 수락한다 - TUTOR만. 기관에 아직 소속이 아니면 소속시키고(기관 초대 수락과 같은 규칙: 다른 기관에
     * 이미 소속돼 있어도 막지 않는다), 그 반의 담임으로 배정한 뒤 초대를 사용 처리한다. 이미 다른 담임이 있는 반이면 담임이
     * 바뀐다(원장이 담임 변경할 때와 같은 정책). 이미 이 선생님이 담임이면 초대만 사용 처리한다.
     *
     * <p>알림: 원장에게 "담임이 됐어요"(homeroom-invite-accepted), 새 담임과 이전 담임에게는 applyHomeroom이 보낸다.
     * 기관 소속 알림(org-tutor-invite-accepted)은 보내지 않는다 - 같은 일로 원장에게 알림 두 개가 가지 않게.
     */
    @Transactional
    public ClassResponse accept(CurrentUser caller, String code) {
        if (caller.role() != Role.TUTOR) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "선생님 계정만 담임 초대를 수락할 수 있어요.", 403);
        }
        ClassHomeroomInvite invite = requireUsable(inviteRepository.lockByShortCode(normalize(code)).orElse(null));
        AppUser tutor = userRepository.findById(caller.userId())
                .filter(user -> user.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.UNAUTHENTICATED, "로그인이 필요해요.", 401));
        ClassGroup classGroup = invite.getClassGroup();
        if (classGroup.getOrganization() == null) {
            throw ApiException.contractError(ErrorCode.INVALID_INVITE, "더 이상 사용할 수 없는 초대 코드예요.", 410);
        }

        organizationTutorService.linkTutor(classGroup.getOrganization(), tutor, null);
        boolean changed = classGroup.getTutor() == null || !classGroup.getTutor().getId().equals(tutor.getId());
        ClassResponse response = classService.applyHomeroom(classGroup, tutor, invite.getId().toString());
        if (changed) {
            notifyDirector(classGroup, tutor, invite);
        }

        invite.setUsedAt(Instant.now());
        invite.setUsedBy(tutor);
        inviteRepository.save(invite);
        return response;
    }

    private void notifyDirector(ClassGroup classGroup, AppUser tutor, ClassHomeroomInvite invite) {
        userRepository
                .findFirstByOrganization_IdAndRoleAndDeletedAtIsNull(classGroup.getOrganization().getId(), Role.DIRECTOR)
                .ifPresent(director -> notificationPublisher.publish(
                        director.getId(),
                        "homeroom-invite-accepted",
                        NotificationText.title(tutor.getDisplayName() + " 선생님이 " + classGroup.getName() + " 담임이 됐어요"),
                        "반 화면에서 담임과 학생 명단을 확인해 보세요.",
                        "/organization/classes/" + classGroup.getId(),
                        "homeroom-invite-accepted:" + invite.getId()));
    }

    private static String normalize(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase();
        if (normalized.isEmpty()) {
            throw notFound();
        }
        return normalized;
    }

    private static ClassHomeroomInvite requireUsable(ClassHomeroomInvite invite) {
        if (invite == null) {
            throw notFound();
        }
        // 이미 썼는지 → 새 코드로 바뀌었는지(만료 전에 바뀐 경우만) → 만료됐는지 순서로 알린다.
        if (invite.getUsedAt() != null) {
            throw gone(USED_DETAIL);
        }
        if (invite.getRevokedAt() != null && invite.getRevokedAt().isBefore(invite.getExpiresAt())) {
            throw gone(REPLACED_DETAIL);
        }
        if (!invite.getExpiresAt().isAfter(Instant.now())) {
            throw gone(EXPIRED_DETAIL);
        }
        return invite;
    }

    private static ApiException gone(String detail) {
        return ApiException.contractError(ErrorCode.INVALID_INVITE, detail, 410);
    }

    private static ApiException notFound() {
        return ApiException.contractError(ErrorCode.NOT_FOUND, "담임 초대 코드를 다시 확인해 주세요.", 404);
    }

    private String generateUniqueShortCode() {
        return joinCodeGenerator.generateUnique(inviteRepository::existsByShortCode,
                () -> ApiException.contractError(ErrorCode.INTERNAL_ERROR, "초대 코드를 만들지 못했어요. 잠시 후 다시 시도해 주세요."));
    }
}

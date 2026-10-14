package com.qstory.backend.org.joinrequest;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.TeacherName;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.joinrequest.JoinRequestDtos.OrganizationJoinRequestResponse;
import com.qstory.backend.org.joinrequest.JoinRequestDtos.TutorJoinRequestResponse;
import com.qstory.backend.org.joinrequest.OrganizationJoinRequest.Status;
import com.qstory.backend.org.repository.OrganizationRepository;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.org.tutor.service.OrganizationTutorService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 선생님이 검색으로 찾은 기관에 소속을 신청하고, 그 기관 원장이 수락·거절한다. 초대 코드(OrganizationTutorService)와
 * 나란히 있는 두 번째 길이다 - 수락하면 초대 수락과 같은 organization_tutor 행이 생긴다(linkTutor 재사용).
 *
 * <ul>
 *   <li>신청은 공공 기관 목록과 연결된 기관에만 - 선생님이 검색으로 찾을 수 있는 곳만 받는다.</li>
 *   <li>대기 중인 신청이 있으면 그것을 그대로 돌려준다(같은 기관에 대기 신청은 하나). 이미 소속이면 409.</li>
 *   <li>원장에게 "신청이 왔어요", 선생님에게 "수락/거절됐어요" 알림(인앱 + 앱 푸시, NotificationPublisher).</li>
 * </ul>
 */
@Service
public class OrganizationJoinRequestService {

    static final String KIND_REQUESTED = "org-join-requested";
    static final String KIND_ACCEPTED = "org-join-accepted";
    static final String KIND_REJECTED = "org-join-rejected";
    static final String DIRECTOR_HREF = "/organization/tutors";
    static final String TUTOR_HREF = "/tutor/join-organization";

    private final OrganizationJoinRequestRepository requestRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationTutorRepository organizationTutorRepository;
    private final OrganizationTutorService organizationTutorService;
    private final AppUserRepository userRepository;
    private final NotificationPublisher notificationPublisher;

    public OrganizationJoinRequestService(
            OrganizationJoinRequestRepository requestRepository,
            OrganizationRepository organizationRepository,
            OrganizationTutorRepository organizationTutorRepository,
            OrganizationTutorService organizationTutorService,
            AppUserRepository userRepository,
            NotificationPublisher notificationPublisher) {
        this.requestRepository = requestRepository;
        this.organizationRepository = organizationRepository;
        this.organizationTutorRepository = organizationTutorRepository;
        this.organizationTutorService = organizationTutorService;
        this.userRepository = userRepository;
        this.notificationPublisher = notificationPublisher;
    }

    /* ---------------------------------------------------------- tutor-side */

    @Transactional
    public TutorJoinRequestResponse create(CurrentUser caller, UUID organizationId) {
        requireTutor(caller);
        if (organizationId == null) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "신청할 기관을 골라 주세요.");
        }
        Organization organization = organizationRepository.findById(organizationId)
                .filter(org -> org.getPublicInstitutionId() != null)
                .orElseThrow(() -> ApiException.contractError(
                        ErrorCode.NOT_FOUND, "신청할 기관을 찾을 수 없어요. 다시 검색해 주세요.", 404));
        if (organizationTutorRepository.findByOrganization_IdAndTutor_Id(organizationId, caller.userId()).isPresent()) {
            throw ApiException.contractError(ErrorCode.ALREADY_ORGANIZATION_MEMBER, "이미 이 기관 소속이에요.");
        }
        var pending = requestRepository.findFirstByOrganization_IdAndTutor_IdAndStatus(
                organizationId, caller.userId(), Status.PENDING);
        if (pending.isPresent()) {
            return TutorJoinRequestResponse.of(pending.get());
        }
        AppUser tutor = userRepository.findById(caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.UNAUTHENTICATED, "로그인이 필요해요.", 401));
        OrganizationJoinRequest saved;
        try {
            saved = requestRepository.saveAndFlush(OrganizationJoinRequest.builder()
                    .id(UUID.randomUUID())
                    .organization(organization)
                    .tutor(tutor)
                    .status(Status.PENDING)
                    .createdAt(Instant.now())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 같은 신청이 동시에 두 번 - 대기 신청 부분 유니크(083)가 하나를 막았다. 먼저 들어간 것을 돌려준다.
            return requestRepository.findFirstByOrganization_IdAndTutor_IdAndStatus(
                            organizationId, caller.userId(), Status.PENDING)
                    .map(TutorJoinRequestResponse::of)
                    .orElseThrow(() -> e);
        }
        userRepository.findFirstByOrganization_IdAndRoleAndDeletedAtIsNull(organizationId, Role.DIRECTOR)
                .ifPresent(director -> notificationPublisher.publish(
                        director.getId(),
                        KIND_REQUESTED,
                        clip(TeacherName.of(tutor.getDisplayName()) + "이 소속을 신청했어요", 160),
                        clip(organization.getName() + " 선생님 관리에서 수락하거나 거절해 주세요.", 400),
                        DIRECTOR_HREF,
                        KIND_REQUESTED + ":" + saved.getId()));
        return TutorJoinRequestResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public List<TutorJoinRequestResponse> listMine(CurrentUser caller) {
        requireTutor(caller);
        return requestRepository.findTop20ByTutor_IdOrderByCreatedAtDesc(caller.userId()).stream()
                .map(TutorJoinRequestResponse::of)
                .toList();
    }

    /** 대기 중인 내 신청을 거둔다. 이미 취소했으면 그대로, 원장이 처리했으면 409. */
    @Transactional
    public TutorJoinRequestResponse cancel(CurrentUser caller, UUID requestId) {
        requireTutor(caller);
        OrganizationJoinRequest request = requestRepository.findById(requestId)
                .filter(r -> r.getTutor().getId().equals(caller.userId()))
                .orElseThrow(JoinRequestNotFound::create);
        if (request.getStatus() == Status.PENDING) {
            request.setStatus(Status.CANCELED);
            request.setDecidedAt(Instant.now());
            requestRepository.save(request);
        } else if (request.getStatus() != Status.CANCELED) {
            throw alreadyDecided();
        }
        return TutorJoinRequestResponse.of(request);
    }

    /* ---------------------------------------------------------- director-side */

    @Transactional(readOnly = true)
    public List<OrganizationJoinRequestResponse> listPending(CurrentUser caller, UUID organizationId) {
        requireOwnedByCaller(caller, organizationId);
        return requestRepository.findForOrganization(organizationId, Status.PENDING).stream()
                .map(OrganizationJoinRequestResponse::of)
                .toList();
    }

    /**
     * 수락 - 초대 코드 수락과 같은 소속(linkTutor)을 만든다. 원장 자신이 한 일이라 원장에게 가는 "소속을 수락했어요" 알림은
     * 보내지 않고(notificationKey null), 선생님에게만 알린다. 그사이 초대 코드로 이미 소속됐어도 그대로 수락 처리한다.
     */
    @Transactional
    public OrganizationJoinRequestResponse accept(CurrentUser caller, UUID organizationId, UUID requestId) {
        Organization organization = requireOwnedByCaller(caller, organizationId);
        OrganizationJoinRequest request = requireRequestOf(organizationId, requestId);
        if (request.getStatus() == Status.ACCEPTED) {
            return OrganizationJoinRequestResponse.of(request);
        }
        if (request.getStatus() != Status.PENDING) {
            throw alreadyDecided();
        }
        AppUser tutor = request.getTutor();
        if (tutor.getDeletedAt() != null) {
            request.setStatus(Status.CANCELED);
            request.setDecidedAt(Instant.now());
            requestRepository.save(request);
            throw ApiException.contractError(ErrorCode.JOIN_REQUEST_ALREADY_DECIDED, "탈퇴한 선생님의 신청이에요.");
        }
        organizationTutorService.linkTutor(organization, tutor, null);
        request.setStatus(Status.ACCEPTED);
        request.setDecidedAt(Instant.now());
        requestRepository.save(request);
        notificationPublisher.publish(
                tutor.getId(),
                KIND_ACCEPTED,
                clip(organization.getName() + " 소속이 됐어요", 160),
                "관리자가 소속 신청을 수락했어요. 이제 기관 반을 맡을 수 있어요.",
                TUTOR_HREF,
                KIND_ACCEPTED + ":" + request.getId());
        return OrganizationJoinRequestResponse.of(request);
    }

    @Transactional
    public OrganizationJoinRequestResponse reject(CurrentUser caller, UUID organizationId, UUID requestId) {
        Organization organization = requireOwnedByCaller(caller, organizationId);
        OrganizationJoinRequest request = requireRequestOf(organizationId, requestId);
        if (request.getStatus() == Status.REJECTED) {
            return OrganizationJoinRequestResponse.of(request);
        }
        if (request.getStatus() != Status.PENDING) {
            throw alreadyDecided();
        }
        request.setStatus(Status.REJECTED);
        request.setDecidedAt(Instant.now());
        requestRepository.save(request);
        notificationPublisher.publish(
                request.getTutor().getId(),
                KIND_REJECTED,
                clip(organization.getName() + " 소속 신청이 거절됐어요", 160),
                "관리자에게 받은 초대 코드로도 참여할 수 있어요.",
                TUTOR_HREF,
                KIND_REJECTED + ":" + request.getId());
        return OrganizationJoinRequestResponse.of(request);
    }

    /* ---------------------------------------------------------- helpers */

    private static void requireTutor(CurrentUser caller) {
        if (caller.role() != Role.TUTOR) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "선생님 계정만 소속을 신청할 수 있어요.", 403);
        }
    }

    private Organization requireOwnedByCaller(CurrentUser caller, UUID organizationId) {
        if (caller.role() != Role.DIRECTOR || caller.orgId() == null || !caller.orgId().equals(organizationId)) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "이 기관에 접근할 권한이 없어요.", 403);
        }
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "기관을 찾을 수 없어요.", 404));
    }

    private OrganizationJoinRequest requireRequestOf(UUID organizationId, UUID requestId) {
        return requestRepository.findById(requestId)
                .filter(r -> r.getOrganization().getId().equals(organizationId))
                .orElseThrow(JoinRequestNotFound::create);
    }

    private static ApiException alreadyDecided() {
        return ApiException.contractError(ErrorCode.JOIN_REQUEST_ALREADY_DECIDED, "이미 처리된 소속 신청이에요.");
    }

    static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static final class JoinRequestNotFound {
        static ApiException create() {
            return ApiException.contractError(ErrorCode.NOT_FOUND, "소속 신청을 찾을 수 없어요.", 404);
        }
    }
}

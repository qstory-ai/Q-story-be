package com.qstory.backend.tutor.service;

import com.qstory.backend.org.service.ClassHomeroomHistoryService;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.dto.ClassResponse;
import com.qstory.backend.org.dto.PastClassResponse;
import com.qstory.backend.org.entity.ClassHomeroomHistory;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.tutor.entity.OrganizationTutor;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.org.util.JoinCodeGenerator;
import com.qstory.backend.tutor.dto.CreateTutorClassRequest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 선생님 관점의 반(class_group) - 자기가 담임인 반이다. 원장이 기관 안에 만들고 담임으로 배정한 반과
 * 선생님이 직접 만든 반(기관 소속이면 기관 반, 아니면 개인 반)이 모두 여기에 나온다. 학생 등록과 수업
 * 생성은 이 목록 안의 반만 가리킬 수 있다({@link #requireVisible}).
 */
@Service
public class TutorClassService {

    private final ClassGroupRepository classGroupRepository;
    private final OrganizationTutorRepository organizationTutorRepository;
    private final AppUserRepository userRepository;
    private final JoinCodeGenerator joinCodeGenerator;
    private final ClassHomeroomHistoryService homeroomHistoryService;

    public TutorClassService(
            ClassGroupRepository classGroupRepository, OrganizationTutorRepository organizationTutorRepository,
            AppUserRepository userRepository, JoinCodeGenerator joinCodeGenerator,
            ClassHomeroomHistoryService homeroomHistoryService) {
        this.classGroupRepository = classGroupRepository;
        this.organizationTutorRepository = organizationTutorRepository;
        this.userRepository = userRepository;
        this.joinCodeGenerator = joinCodeGenerator;
        this.homeroomHistoryService = homeroomHistoryService;
    }

    /** 지금 맡은 반 - 지난 반(076)은 빼고, 예전에 맡았던 반과 함께 {@link #listPast}로 따로 본다. */
    @Transactional(readOnly = true)
    public List<ClassResponse> listVisible(CurrentUser caller) {
        return classGroupRepository.findByTutor_IdOrderByCreatedAtAsc(caller.userId()).stream()
                .filter(classGroup -> !classGroup.isArchived())
                .map(ClassResponse::of)
                .toList();
    }

    /**
     * 예전에 맡았던 반(076) - 담임 이력에 있지만 지금은 담임이 아닌 반, 그리고 지금도 담임이지만 지난 반으로 보관된 반.
     * 기관 반만, 그리고 아직 그 기관 소속일 때만(기관을 떠난 선생님에게는 보이지 않는다). 최근에 맡은 반부터.
     * 이 반들의 상세·리포트는 자기가 진행한 수업만 보인다(ClassService).
     */
    @Transactional(readOnly = true)
    public List<PastClassResponse> listPast(CurrentUser caller) {
        Map<UUID, PastClassResponse> byClass = new LinkedHashMap<>();
        Map<UUID, Boolean> membership = new HashMap<>();
        for (ClassHomeroomHistory entry : homeroomHistoryService.listByTutor(caller.userId())) {
            ClassGroup classGroup = entry.getClassGroup();
            if (classGroup.getOrganization() == null) continue;
            boolean current = classGroup.getTutor() != null && classGroup.getTutor().getId().equals(caller.userId());
            if (current && !classGroup.isArchived()) continue;
            UUID organizationId = classGroup.getOrganization().getId();
            boolean member = membership.computeIfAbsent(organizationId, id ->
                    organizationTutorRepository.findByOrganization_IdAndTutor_Id(id, caller.userId()).isPresent());
            if (!member) continue;
            PastClassResponse previous = byClass.get(classGroup.getId());
            Instant ledFrom = previous == null || entry.getStartedAt().isBefore(previous.ledFrom())
                    ? entry.getStartedAt() : previous.ledFrom();
            Instant ledUntil = current ? null : latest(previous == null ? null : previous.ledUntil(), entry.getEndedAt());
            byClass.put(classGroup.getId(), new PastClassResponse(
                    classGroup.getId(), organizationId, classGroup.getOrganization().getName(), classGroup.getName(),
                    classGroup.getArchivedAt(), ledFrom, ledUntil));
        }
        return byClass.values().stream()
                .sorted(Comparator.comparing(
                        (PastClassResponse past) -> past.ledUntil() == null ? Instant.MAX : past.ledUntil()).reversed())
                .toList();
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    @Transactional
    public ClassResponse create(CurrentUser caller, CreateTutorClassRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "반 이름을 입력해 주세요.");
        }
        AppUser tutor = userRepository.getReferenceById(caller.userId());
        Organization organization = null;
        if (request.organizationId() != null) {
            organization = organizationTutorRepository
                    .findByOrganization_IdAndTutor_Id(request.organizationId(), caller.userId())
                    .map(OrganizationTutor::getOrganization)
                    .orElseThrow(() -> ApiException.contractError(
                            ErrorCode.FORBIDDEN, "소속되지 않은 기관에는 반을 만들 수 없어요.", 403));
        }
        ClassGroup saved = classGroupRepository.save(ClassGroup.builder()
                .organization(organization)
                .tutor(tutor)
                .name(request.name().trim())
                .joinCode(generateUniqueJoinCode())
                .createdAt(Instant.now())
                .build());
        homeroomHistoryService.start(saved, tutor, saved.getCreatedAt());
        return ClassResponse.of(saved);
    }

    /**
     * 학생 등록·수업 생성에서 반 id를 받았을 때 - 선생님이 볼 수 있는 반(자기가 담임인 반)이 아니면 404.
     * 같은 기관의 다른 선생님 반은 보이지 않는다.
     */
    @Transactional(readOnly = true)
    public ClassGroup requireVisible(CurrentUser caller, UUID classGroupId) {
        ClassGroup classGroup = classGroupRepository.findByIdAndTutor_Id(classGroupId, caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "반을 찾을 수 없어요.", 404));
        // 지난 반(076)에는 새 수업을 만들거나 수업을 옮겨 넣지 않는다.
        if (classGroup.isArchived()) {
            throw ApiException.contractError(
                    ErrorCode.CLASS_ARCHIVED, "지난 반이라 수업을 더 만들 수 없어요. 원장 선생님께 확인해 주세요.", 409);
        }
        return classGroup;
    }

    private String generateUniqueJoinCode() {
        return joinCodeGenerator.generateUnique(classGroupRepository::existsByJoinCode,
                () -> ApiException.contractError(ErrorCode.INTERNAL_ERROR, "반 코드를 생성하지 못했어요. 다시 시도해 주세요.", 500));
    }
}

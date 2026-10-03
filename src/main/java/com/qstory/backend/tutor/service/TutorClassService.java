package com.qstory.backend.tutor.service;

import com.qstory.backend.org.service.ClassHomeroomHistoryService;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.dto.ClassResponse;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.tutor.entity.OrganizationTutor;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.org.util.JoinCodeGenerator;
import com.qstory.backend.tutor.dto.CreateTutorClassRequest;
import java.time.Instant;
import java.util.List;
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

    @Transactional(readOnly = true)
    public List<ClassResponse> listVisible(CurrentUser caller) {
        return classGroupRepository.findByTutor_IdOrderByCreatedAtAsc(caller.userId()).stream()
                .map(ClassResponse::of)
                .toList();
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
        return classGroupRepository.findByIdAndTutor_Id(classGroupId, caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "반을 찾을 수 없어요.", 404));
    }

    private String generateUniqueJoinCode() {
        return joinCodeGenerator.generateUnique(classGroupRepository::existsByJoinCode,
                () -> ApiException.contractError(ErrorCode.INTERNAL_ERROR, "반 코드를 생성하지 못했어요. 다시 시도해 주세요.", 500));
    }
}

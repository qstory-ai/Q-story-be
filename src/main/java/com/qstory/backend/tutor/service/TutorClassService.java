package com.qstory.backend.tutor.service;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 선생님 관점의 반(class_group). 선생님이 "볼 수 있는" 반은 두 종류다:
 * (1) 자신이 만든 반(class_group.tutor_id = 나), (2) 자신이 소속된 기관이 만든 반. 학생 등록과
 * 수업 생성은 이 목록 안의 반만 가리킬 수 있다({@link #requireVisible}).
 *
 * <p>ClassService(기관 관리자 관점)와 같은 테이블을 쓰지만 기관 없는 반도 만들 수 있다 - 그런 반은
 * 부모 가입 코드·반 계정 같은 기관 전용 흐름에서는 제외된다(ClassService가 organization null을 거른다).
 */
@Service
public class TutorClassService {

    private final ClassGroupRepository classGroupRepository;
    private final OrganizationTutorRepository organizationTutorRepository;
    private final AppUserRepository userRepository;
    private final JoinCodeGenerator joinCodeGenerator;

    public TutorClassService(
            ClassGroupRepository classGroupRepository, OrganizationTutorRepository organizationTutorRepository,
            AppUserRepository userRepository, JoinCodeGenerator joinCodeGenerator) {
        this.classGroupRepository = classGroupRepository;
        this.organizationTutorRepository = organizationTutorRepository;
        this.userRepository = userRepository;
        this.joinCodeGenerator = joinCodeGenerator;
    }

    @Transactional(readOnly = true)
    public List<ClassResponse> listVisible(CurrentUser caller) {
        return visibleClasses(caller.userId()).stream().map(ClassResponse::of).toList();
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
        return ClassResponse.of(saved);
    }

    /** 학생 등록·수업 생성에서 반 id를 받았을 때 - 선생님이 볼 수 있는 반이 아니면 404. */
    @Transactional(readOnly = true)
    public ClassGroup requireVisible(CurrentUser caller, UUID classGroupId) {
        return visibleClasses(caller.userId()).stream()
                .filter(classGroup -> classGroup.getId().equals(classGroupId))
                .findFirst()
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "반을 찾을 수 없어요.", 404));
    }

    private List<ClassGroup> visibleClasses(UUID tutorId) {
        // LinkedHashMap으로 중복 제거 - 기관 소속 선생님이 기관 안에 만든 반은 두 조회에 다 걸린다.
        Map<UUID, ClassGroup> byId = new LinkedHashMap<>();
        for (ClassGroup own : classGroupRepository.findByTutor_IdOrderByCreatedAtAsc(tutorId)) {
            byId.put(own.getId(), own);
        }
        List<UUID> organizationIds = new ArrayList<>();
        for (OrganizationTutor link : organizationTutorRepository.findByTutor_IdOrderByJoinedAtAsc(tutorId)) {
            organizationIds.add(link.getOrganization().getId());
        }
        if (!organizationIds.isEmpty()) {
            for (ClassGroup orgClass : classGroupRepository.findByOrganization_IdInOrderByCreatedAtAsc(organizationIds)) {
                byId.putIfAbsent(orgClass.getId(), orgClass);
            }
        }
        return new ArrayList<>(byId.values());
    }

    private String generateUniqueJoinCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = joinCodeGenerator.generate();
            if (!classGroupRepository.existsByJoinCode(code)) return code;
        }
        throw ApiException.contractError(ErrorCode.INTERNAL_ERROR, "반 코드를 생성하지 못했어요. 다시 시도해 주세요.", 500);
    }
}

package com.qstory.backend.tutor.lessonplan.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lessonplan.dto.CreateTutorLessonPlanRequest;
import com.qstory.backend.tutor.lessonplan.dto.TutorLessonPlanResponse;
import com.qstory.backend.tutor.lessonplan.entity.TutorLessonPlan;
import com.qstory.backend.tutor.lessonplan.repository.TutorLessonPlanRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방문 선생님이 특정 학생의 다음 수업에 쓸 이야기를 담아 두는 리스트의 CRUD. 저장은 (student,
 * story) 조합으로 최대 1건이라 같은 조합을 다시 add해도 idempotent하게 기존 계획을 반환한다.
 * 학생 소유권은 TutorStudentRepository.findByIdAndTutor_Id를 통해서만 확인한다 - 다른 선생님의
 * 학생에게 담으려는 시도는 404로 응답한다(존재조차 노출하지 않는다).
 */
@Service
public class TutorLessonPlanService {

    private static final int MAX_STORY_ID_LENGTH = 64;

    private final TutorLessonPlanRepository lessonPlanRepository;
    private final TutorStudentRepository tutorStudentRepository;
    private final AppUserRepository userRepository;

    public TutorLessonPlanService(
            TutorLessonPlanRepository lessonPlanRepository,
            TutorStudentRepository tutorStudentRepository,
            AppUserRepository userRepository) {
        this.lessonPlanRepository = lessonPlanRepository;
        this.tutorStudentRepository = tutorStudentRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<TutorLessonPlanResponse> listMine(CurrentUser caller) {
        return lessonPlanRepository.findByTutor_IdOrderByAddedAtDesc(caller.userId()).stream()
                .map(TutorLessonPlanResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TutorLessonPlanResponse> listForStudent(CurrentUser caller, UUID studentId) {
        // 소유권 확인부터 - 접근 불가면 404, 이후 목록 조회는 소유가 이미 검증된 뒤에만.
        requireOwnedStudent(caller, studentId);
        return lessonPlanRepository.findByTutorStudent_IdOrderByAddedAtDesc(studentId).stream()
                .map(TutorLessonPlanResponse::of)
                .toList();
    }

    @Transactional
    public TutorLessonPlanResponse create(CurrentUser caller, CreateTutorLessonPlanRequest request) {
        if (request.tutorStudentId() == null) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "학생을 선택해 주세요.");
        }
        String storyId = normalizeStoryId(request.storyId());
        TutorStudent student = requireOwnedStudent(caller, request.tutorStudentId());
        return lessonPlanRepository.findByTutorStudent_IdAndStoryId(student.getId(), storyId)
                .map(TutorLessonPlanResponse::of)
                .orElseGet(() -> {
                    AppUser tutor = userRepository.getReferenceById(caller.userId());
                    TutorLessonPlan saved = lessonPlanRepository.save(TutorLessonPlan.builder()
                            .tutor(tutor)
                            .tutorStudent(student)
                            .storyId(storyId)
                            .addedAt(Instant.now())
                            .build());
                    return TutorLessonPlanResponse.of(saved);
                });
    }

    @Transactional
    public void delete(CurrentUser caller, UUID planId) {
        lessonPlanRepository.findByIdAndTutor_Id(planId, caller.userId())
                .ifPresent(lessonPlanRepository::delete);
    }

    private TutorStudent requireOwnedStudent(CurrentUser caller, UUID studentId) {
        return tutorStudentRepository.findByIdAndTutor_Id(studentId, caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "학생을 찾을 수 없어요.", 404));
    }

    private static String normalizeStoryId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "storyId가 필요해요.");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_STORY_ID_LENGTH) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "storyId 길이가 너무 길어요.");
        }
        return trimmed;
    }
}

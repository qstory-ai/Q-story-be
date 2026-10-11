package com.qstory.backend.org.tutor.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.tutor.dto.TutorStudentResponse;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.entity.Lesson;
import com.qstory.backend.tutor.lesson.dto.LessonResponse;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기관 관리자(DIRECTOR)가 소속 선생님이 맡은 학생·수업을 읽는 경로. 범위 검사는 세 가지다 - 호출자가 그 기관의
 * DIRECTOR인가, 그 선생님이 organization_tutor로 이 기관에 연결돼 있는가, 그리고 학생·수업이 이 기관의 반 것인가(보안 점검
 * M8). 선생님은 여러 기관에 소속되거나 기관 밖 개인 반을 함께 가질 수 있어, 선생님 기준으로만 고르면 다른 기관·개인 반의
 * 아이 정보가 이 원장에게 보였다. 소속을 해제하면(OrganizationTutorService.unlinkTutor) 곧바로 보이지 않는다.
 * 읽기 전용이며 선생님 쪽 응답 DTO를 그대로 재사용한다.
 */
@Service
public class OrganizationTutorInsightService {

    private final OrganizationTutorRepository organizationTutorRepository;
    private final TutorStudentRepository tutorStudentRepository;
    private final LessonRepository lessonRepository;

    public OrganizationTutorInsightService(
            OrganizationTutorRepository organizationTutorRepository,
            TutorStudentRepository tutorStudentRepository,
            LessonRepository lessonRepository) {
        this.organizationTutorRepository = organizationTutorRepository;
        this.tutorStudentRepository = tutorStudentRepository;
        this.lessonRepository = lessonRepository;
    }

    @Transactional(readOnly = true)
    public List<TutorStudentResponse> listStudents(CurrentUser caller, UUID organizationId, UUID tutorId) {
        requireLinkedTutor(caller, organizationId, tutorId);
        return tutorStudentRepository.findByTutor_IdAndDeletedAtIsNullOrderByCreatedAtAsc(tutorId).stream()
                .filter(student -> inOrganization(student, organizationId))
                .map(TutorStudentResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LessonResponse> listLessons(CurrentUser caller, UUID organizationId, UUID tutorId) {
        requireLinkedTutor(caller, organizationId, tutorId);
        return lessonRepository.findByTutor_IdOrderByScheduledAtAscCreatedAtAsc(tutorId).stream()
                .filter(lesson -> inOrganization(lesson, organizationId))
                .map(LessonResponse::of)
                .toList();
    }

    static boolean inOrganization(TutorStudent student, UUID organizationId) {
        return inOrganization(student.getClassGroup(), organizationId);
    }

    /** 반 수업은 그 반의 기관, 반 없이 학생만 고른 수업은 학생 중 하나라도 이 기관 반이면. */
    static boolean inOrganization(Lesson lesson, UUID organizationId) {
        if (lesson.getClassGroup() != null) {
            return inOrganization(lesson.getClassGroup(), organizationId);
        }
        return lesson.getStudents() != null
                && lesson.getStudents().stream().anyMatch(student -> inOrganization(student, organizationId));
    }

    private static boolean inOrganization(ClassGroup classGroup, UUID organizationId) {
        return classGroup != null
                && classGroup.getOrganization() != null
                && organizationId.equals(classGroup.getOrganization().getId());
    }

    private void requireLinkedTutor(CurrentUser caller, UUID organizationId, UUID tutorId) {
        if (caller.orgId() == null || !caller.orgId().equals(organizationId)) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "이 기관에 접근할 권한이 없어요.", 403);
        }
        organizationTutorRepository.findByOrganization_IdAndTutor_Id(organizationId, tutorId)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "이 기관에 소속된 선생님이 아니에요.", 404));
    }
}

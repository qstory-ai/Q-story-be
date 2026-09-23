package com.qstory.backend.org.tutor.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.tutor.dto.TutorStudentResponse;
import com.qstory.backend.tutor.lesson.dto.LessonResponse;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기관 관리자(DIRECTOR)가 소속 선생님이 맡은 학생·수업을 읽는 경로. 제품 결정: 기관은 소속
 * 선생님의 학생·수업을 "전부" 본다(학생마다 기관을 따로 표시하지 않는다). 그래서 범위 검사는
 * 두 가지뿐이다 - 호출자가 그 기관의 DIRECTOR인가, 그 선생님이 organization_tutor로 이 기관에
 * 연결돼 있는가. 소속을 해제하면(OrganizationTutorService.unlinkTutor) 곧바로 보이지 않는다.
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
        return tutorStudentRepository.findByTutor_IdOrderByCreatedAtAsc(tutorId).stream()
                .map(TutorStudentResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LessonResponse> listLessons(CurrentUser caller, UUID organizationId, UUID tutorId) {
        requireLinkedTutor(caller, organizationId, tutorId);
        return lessonRepository.findByTutor_IdOrderByScheduledAtAscCreatedAtAsc(tutorId).stream()
                .map(LessonResponse::of)
                .toList();
    }

    private void requireLinkedTutor(CurrentUser caller, UUID organizationId, UUID tutorId) {
        if (caller.orgId() == null || !caller.orgId().equals(organizationId)) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "이 기관에 접근할 권한이 없어요.", 403);
        }
        organizationTutorRepository.findByOrganization_IdAndTutor_Id(organizationId, tutorId)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "이 기관에 소속된 선생님이 아니에요.", 404));
    }
}

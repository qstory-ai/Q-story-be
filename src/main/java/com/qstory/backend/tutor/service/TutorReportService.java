package com.qstory.backend.tutor.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.storyreport.dto.StoryCompletionSummary;
import com.qstory.backend.storyreport.repository.StoryCompletionRepository;
import com.qstory.backend.tutor.dto.TutorReportSummary;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리포트 조회 전용 - 등록/일정/초대(TutorStudentService)와 분리해 둔 이유는 이 서비스만
 * StoryCompletionRepository(storyreport 패키지)에 의존하기 때문이다. 두 방향 모두 "가정 서재와
 * 선생님 수업은 절대 섞이지 않는다"는 원칙을 지킨다: 선생님 쪽은 자기 학생의 기록만, 부모 쪽은
 * tutorStudent가 채워진(=선생님이 진행한) 기록만 본다.
 */
@Service
public class TutorReportService {

    private final StoryCompletionRepository storyCompletionRepository;
    private final TutorStudentRepository tutorStudentRepository;

    public TutorReportService(StoryCompletionRepository storyCompletionRepository, TutorStudentRepository tutorStudentRepository) {
        this.storyCompletionRepository = storyCompletionRepository;
        this.tutorStudentRepository = tutorStudentRepository;
    }

    /** 선생님 자신이 등록한 학생 하나에 대한 세션 기록 - 소유하지 않은 학생 id면 404. */
    @Transactional(readOnly = true)
    public List<StoryCompletionSummary> listStudentCompletions(CurrentUser caller, UUID studentId) {
        tutorStudentRepository.findByIdAndTutor_Id(studentId, caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "학생을 찾을 수 없어요.", 404));
        return storyCompletionRepository.findByTutorStudent_IdOrderByCompletedAtDesc(studentId).stream()
                .map(StoryCompletionSummary::of)
                .toList();
    }

    /**
     * 부모가 연결된 선생님(들)로부터 받은 기록 - 부모 자신의 가정 완주 기록은 여기 절대 섞이지
     * 않는다. @Transactional(readOnly=true) 필수 - TutorReportSummary.of()가 지연 로딩된
     * completion.getTutorStudent().getName()/.getTutor().getDisplayName()을 읽는다.
     */
    @Transactional(readOnly = true)
    public List<TutorReportSummary> listReportsForParent(CurrentUser caller) {
        return storyCompletionRepository.findByTutorStudent_LinkedParentUser_IdOrderByCompletedAtDesc(caller.userId()).stream()
                .map(TutorReportSummary::of)
                .toList();
    }
}

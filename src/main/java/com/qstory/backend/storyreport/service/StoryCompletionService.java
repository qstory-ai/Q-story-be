package com.qstory.backend.storyreport.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.storyreport.dto.RecordStoryCompletionRequest;
import com.qstory.backend.storyreport.dto.StoryCompletionDetail;
import com.qstory.backend.storyreport.dto.StoryCompletionSummary;
import com.qstory.backend.storyreport.entity.StoryCompletion;
import com.qstory.backend.storyreport.repository.StoryCompletionRepository;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoryCompletionService {

    private final StoryCompletionRepository repository;
    private final AppUserRepository userRepository;
    private final TutorStudentRepository tutorStudentRepository;

    public StoryCompletionService(
            StoryCompletionRepository repository, AppUserRepository userRepository,
            TutorStudentRepository tutorStudentRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.tutorStudentRepository = tutorStudentRepository;
    }

    @Transactional
    public StoryCompletionSummary record(CurrentUser caller, RecordStoryCompletionRequest request) {
        if (request.storyId() == null || request.storyId().isBlank()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "storyId가 필요해요.");
        }
        AppUser user = userRepository.findById(caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.UNAUTHENTICATED, "로그인이 필요해요.", 401));
        // tutorStudentId는 caller(=선생님) 소유의 학생일 때만 세션 출처로 인정한다 - 남의 학생 id를
        // 끼워 넣어 부모 쪽 공유 리스트에 끼어드는 걸 막는다.
        TutorStudent tutorStudent = request.tutorStudentId() == null
                ? null
                : tutorStudentRepository.findByIdAndTutor_Id(request.tutorStudentId(), caller.userId())
                        .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "학생을 찾을 수 없어요.", 404));
        StoryCompletion completion = repository.save(StoryCompletion.builder()
                .user(user)
                .tutorStudent(tutorStudent)
                .storyId(request.storyId())
                .completedAt(Instant.now())
                .durationSeconds(request.durationSeconds())
                .outcomes(request.outcomes() == null ? List.of() : request.outcomes())
                .createdAt(Instant.now())
                .build());
        return StoryCompletionSummary.of(completion);
    }

    public List<StoryCompletionSummary> list(CurrentUser caller) {
        return repository.findByUser_IdOrderByCompletedAtDesc(caller.userId()).stream()
                .map(StoryCompletionSummary::of)
                .toList();
    }

    public StoryCompletionDetail get(CurrentUser caller, UUID id) {
        StoryCompletion completion = repository.findByIdAndUser_Id(id, caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "기록을 찾을 수 없어요.", 404));
        return StoryCompletionDetail.of(completion);
    }
}

package com.qstory.backend.storyreport.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.parent.child.entity.Child;
import com.qstory.backend.parent.child.repository.ChildRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoryCompletionService {

    private static final int RECENT_LIMIT_MAX = 20;

    private final StoryCompletionRepository repository;
    private final AppUserRepository userRepository;
    private final TutorStudentRepository tutorStudentRepository;
    private final ChildRepository childRepository;
    private final NotificationPublisher notificationPublisher;

    public StoryCompletionService(
            StoryCompletionRepository repository, AppUserRepository userRepository,
            TutorStudentRepository tutorStudentRepository, ChildRepository childRepository,
            NotificationPublisher notificationPublisher) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.tutorStudentRepository = tutorStudentRepository;
        this.childRepository = childRepository;
        this.notificationPublisher = notificationPublisher;
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
        // childId도 마찬가지 - caller(=부모)가 소유한 아이 프로필일 때만 인정. 방문 선생님의 세션은
        // childId를 보내지 않는 것이 관례이지만, 만약 함께 왔다면 그건 이 부모 계정의 아이가 아니라
        // 404로 응답한다(다른 부모의 아이 id로 리포트를 오염시키는 걸 막는다).
        Child child = request.childId() == null
                ? null
                : childRepository.findByIdAndParent_Id(request.childId(), caller.userId())
                        .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "아이 프로필을 찾을 수 없어요.", 404));
        StoryCompletion completion = repository.save(StoryCompletion.builder()
                .user(user)
                .tutorStudent(tutorStudent)
                .child(child)
                .storyId(request.storyId())
                .completedAt(Instant.now())
                .durationSeconds(request.durationSeconds())
                .outcomes(request.outcomes() == null ? List.of() : request.outcomes())
                .createdAt(Instant.now())
                .build());
        // 튜터 세션의 완주 기록은 부모(=linkedParentUser)에게 새 리포트가 도착했다고 알린다.
        // linkedParentUser가 null이면(=아직 부모 초대 수락 전 상태) 알림을 만들 대상이 없어 건너뛴다.
        // dedupKey는 completion.id로 안정화 - 트랜잭션 재시도로 record()가 두 번 호출돼도 알림은 하나만.
        if (tutorStudent != null && tutorStudent.getLinkedParentUser() != null) {
            AppUser parent = tutorStudent.getLinkedParentUser();
            notificationPublisher.publish(
                    parent.getId(),
                    "tutor-report",
                    tutorStudent.getName() + " 선생님 수업 기록이 도착했어요",
                    tutorStudent.getName() + "의 오늘 이야기 세션을 리포트로 확인해 보세요.",
                    "/reports/" + completion.getId(),
                    "tutor-report:" + completion.getId());
        }
        return StoryCompletionSummary.of(completion);
    }

    /**
     * childId가 주어지면 그 아이 프로필의 완주만, 없으면 caller의 전체 완주.
     * 소유 검증(child가 caller의 것인가)은 목록 조회에도 적용해야 하는데, repository 쿼리 자체가
     * user_id로 스코프되어 있어(다른 부모의 child_id를 넣으면 결과 자체가 비므로) 별도 예외는
     * 던지지 않고 조용히 빈 목록으로 응답한다.
     */
    public List<StoryCompletionSummary> list(CurrentUser caller, UUID childId) {
        var completions = childId == null
                ? repository.findByUser_IdOrderByCompletedAtDesc(caller.userId())
                : repository.findByUser_IdAndChild_IdOrderByCompletedAtDesc(caller.userId(), childId);
        return completions.stream().map(StoryCompletionSummary::of).toList();
    }

    /** 최근 N회의 전체 outcomes를 함께 반환한다 - 프론트가 여러 회차를 가로지르는 누적 트렌드(반복 접근, 관심 주제)를 계산할 때 쓴다. */
    public List<StoryCompletionDetail> recent(CurrentUser caller, int limit, UUID childId) {
        int boundedLimit = Math.max(1, Math.min(limit, RECENT_LIMIT_MAX));
        var page = PageRequest.of(0, boundedLimit);
        var completions = childId == null
                ? repository.findByUser_IdOrderByCompletedAtDesc(caller.userId(), page)
                : repository.findByUser_IdAndChild_IdOrderByCompletedAtDesc(caller.userId(), childId, page);
        return completions.stream().map(StoryCompletionDetail::of).toList();
    }

    public StoryCompletionDetail get(CurrentUser caller, UUID id) {
        StoryCompletion completion = repository.findByIdAndUser_Id(id, caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "기록을 찾을 수 없어요.", 404));
        return StoryCompletionDetail.of(completion);
    }
}

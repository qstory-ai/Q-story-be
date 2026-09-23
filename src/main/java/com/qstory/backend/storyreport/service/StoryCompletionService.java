package com.qstory.backend.storyreport.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.companionchat.entity.CompanionChatTurn;
import com.qstory.backend.companionchat.repository.CompanionChatTurnRepository;
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
import com.qstory.backend.tutor.lesson.LessonStatus;
import com.qstory.backend.tutor.lesson.entity.Lesson;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
    private final CompanionChatTurnRepository companionChatTurnRepository;
    private final LessonRepository lessonRepository;

    public StoryCompletionService(
            StoryCompletionRepository repository, AppUserRepository userRepository,
            TutorStudentRepository tutorStudentRepository, ChildRepository childRepository,
            NotificationPublisher notificationPublisher,
            CompanionChatTurnRepository companionChatTurnRepository,
            LessonRepository lessonRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.tutorStudentRepository = tutorStudentRepository;
        this.childRepository = childRepository;
        this.notificationPublisher = notificationPublisher;
        this.companionChatTurnRepository = companionChatTurnRepository;
        this.lessonRepository = lessonRepository;
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
        // childId도 마찬가지 - caller(=부모)가 소유한 아이 프로필일 때만 인정. 선생님의 세션은
        // childId를 보내지 않는 것이 관례이지만, 만약 함께 왔다면 그건 이 부모 계정의 아이가 아니라
        // 404로 응답한다(다른 부모의 아이 id로 리포트를 오염시키는 걸 막는다).
        Child child = request.childId() == null
                ? null
                : childRepository.findByIdAndParent_Id(request.childId(), caller.userId())
                        .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "아이 프로필을 찾을 수 없어요.", 404));
        // 수업에서 시작한 세션 - caller가 소유한 수업이어야 한다. 반 수업이면 참여 학생 전원에게 기록이
        // 남는다(예전엔 프론트가 students[0]만 넘겨 첫 학생에게만 남았다).
        Lesson lesson = request.lessonId() == null
                ? null
                : lessonRepository.findByIdAndTutor_Id(request.lessonId(), caller.userId())
                        .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "수업을 찾을 수 없어요.", 404));
        Map<String, Object> companionChatSummary = summarizeCompanionChat(request.companionConversationId());
        Instant now = Instant.now();

        List<TutorStudent> participants = new ArrayList<>();
        if (lesson != null && !lesson.getStudents().isEmpty()) {
            participants.addAll(lesson.getStudents());
            // 수업 학생 목록에 없는 tutorStudentId가 함께 왔으면(수업 편집 전에 시작한 세션 등) 그 학생도 포함.
            if (tutorStudent != null && participants.stream().noneMatch(p -> p.getId().equals(tutorStudent.getId()))) {
                participants.add(tutorStudent);
            }
        } else if (tutorStudent != null) {
            participants.add(tutorStudent);
        }

        // 수업이 아직 예정 상태였다면 이야기를 실제로 시작한 것이므로 진행 중으로 올린다.
        if (lesson != null && lesson.getStatus() == LessonStatus.SCHEDULED) {
            lesson.setStatus(LessonStatus.IN_PROGRESS);
            lesson.setStartedAt(now);
            lesson.setUpdatedAt(now);
            lessonRepository.save(lesson);
        }

        if (participants.isEmpty()) {
            // 가정 세션(부모/반 계정) 또는 학생이 없는 수업 - 기록 하나.
            return StoryCompletionSummary.of(
                    saveCompletion(user, lesson, null, child, request, companionChatSummary, now));
        }
        StoryCompletion primary = null;
        for (TutorStudent participant : participants) {
            // 선생님 세션은 childId를 보내지 않지만, 부모가 초대를 수락하며 연결한 아이 프로필이 학생에
            // 붙어 있으면 그 아이의 기록으로 남긴다 - 부모 홈의 아이별 리포트와 선생님 리포트가 같은
            // 아이를 가리키게 된다.
            Child participantChild = participant.getChild() != null ? participant.getChild() : child;
            StoryCompletion completion = saveCompletion(
                    user, lesson, participant, participantChild, request, companionChatSummary, now);
            notifyLinkedParent(participant, completion);
            boolean isRequested = tutorStudent != null && participant.getId().equals(tutorStudent.getId());
            if (primary == null || isRequested) primary = completion;
        }
        return StoryCompletionSummary.of(primary);
    }

    private StoryCompletion saveCompletion(
            AppUser user, Lesson lesson, TutorStudent tutorStudent, Child child,
            RecordStoryCompletionRequest request, Map<String, Object> companionChatSummary, Instant now) {
        return repository.save(StoryCompletion.builder()
                .user(user)
                .organization(user.getOrganization())
                .classGroup(user.getClassGroup())
                .tutorStudent(tutorStudent)
                .child(child)
                .lesson(lesson)
                .storyId(request.storyId())
                .completedAt(now)
                .durationSeconds(request.durationSeconds())
                .outcomes(request.outcomes() == null ? List.of() : request.outcomes())
                .companionChatSummary(companionChatSummary)
                .createdAt(now)
                .build());
    }

    /**
     * 튜터 세션의 완주 기록은 부모(=linkedParentUser)에게 새 리포트가 도착했다고 알린다.
     * linkedParentUser가 null이면(=아직 부모 초대 수락 전 상태) 알림을 만들 대상이 없어 건너뛴다.
     * dedupKey는 completion.id로 안정화 - 트랜잭션 재시도로 record()가 두 번 호출돼도 알림은 하나만.
     */
    private void notifyLinkedParent(TutorStudent tutorStudent, StoryCompletion completion) {
        if (tutorStudent.getLinkedParentUser() == null) return;
        AppUser parent = tutorStudent.getLinkedParentUser();
        notificationPublisher.publish(
                parent.getId(),
                "tutor-report",
                tutorStudent.getName() + " 선생님 수업 기록이 도착했어요",
                tutorStudent.getName() + "의 오늘 이야기 세션을 리포트로 확인해 보세요.",
                "/reports/" + completion.getId(),
                "tutor-report:" + completion.getId());
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

    /**
     * 본인 가정 완주 기록뿐 아니라, 부모가 연결한 선생님 수업의 완주 기록도 읽을 수 있다.
     * 튜터 리포트 목록은 이미 linkedParentUser 기준으로 노출되는데 상세에서 다시 user_id(튜터)
     * 만 검사하면 부모가 알림을 눌러도 404가 되는 불일치가 생긴다. 반대로 연결되지 않은 부모는
     * 존재 여부를 알 수 없도록 같은 NOT_FOUND로 처리한다.
     */
    @Transactional(readOnly = true)
    public StoryCompletionDetail get(CurrentUser caller, UUID id) {
        StoryCompletion completion = repository.findById(id)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "기록을 찾을 수 없어요.", 404));
        boolean isSessionOwner = completion.getUser().getId().equals(caller.userId());
        boolean isLinkedParent = completion.getTutorStudent() != null
                && completion.getTutorStudent().getLinkedParentUser() != null
                && completion.getTutorStudent().getLinkedParentUser().getId().equals(caller.userId());
        if (!isSessionOwner && !isLinkedParent) {
            throw ApiException.contractError(ErrorCode.NOT_FOUND, "기록을 찾을 수 없어요.", 404);
        }
        return StoryCompletionDetail.of(completion);
    }

    /**
     * conversationId에 해당하는 companion_chat_turn 행들을 topic/tone/value 라벨별 빈도와
     * 전체 턴 수로 접어 넣는다. 리포트 화면(ReportContent)이 그대로 렌더할 수 있게 정렬된 배열 형태.
     * conversationId가 null이거나 대응 턴이 없으면 null - 실시간 리포트가 이 필드로 렌더 여부를 결정.
     */
    Map<String, Object> summarizeCompanionChat(UUID conversationId) {
        if (conversationId == null) {
            return null;
        }
        List<CompanionChatTurn> turns = companionChatTurnRepository.findByConversationId(conversationId);
        if (turns.isEmpty()) {
            return null;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("turnCount", turns.size());
        summary.put("topics", labelCounts(turns, CompanionChatTurn::getTopicTag));
        summary.put("tones", labelCounts(turns, CompanionChatTurn::getToneTag));
        summary.put("values", labelCounts(turns, CompanionChatTurn::getValueTag));
        return summary;
    }

    private static List<Map<String, Object>> labelCounts(
            List<CompanionChatTurn> turns, java.util.function.Function<CompanionChatTurn, String> extractor) {
        Map<String, Integer> counts = new TreeMap<>();
        for (CompanionChatTurn turn : turns) {
            String label = extractor.apply(turn);
            if (label == null || label.isBlank()) continue;
            counts.merge(label, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("label", entry.getKey());
                    row.put("count", entry.getValue());
                    return row;
                })
                .toList();
    }
}

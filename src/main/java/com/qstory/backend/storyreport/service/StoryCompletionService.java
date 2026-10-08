package com.qstory.backend.storyreport.service;

import com.qstory.backend.common.util.TeacherName;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.companionchat.entity.CompanionChatTurn;
import com.qstory.backend.companionchat.repository.CompanionChatTurnRepository;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.parent.child.entity.Child;
import com.qstory.backend.parent.child.repository.ChildRepository;
import com.qstory.backend.playsession.service.PlaySessionService;
import com.qstory.backend.reportanalysis.service.ReportAnalysisStore;
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
import java.util.LinkedHashSet;
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
    private final PlaySessionService playSessionService;
    private final ReportAnalysisStore reportAnalysisStore;

    static final int TEACHER_NOTE_MAX = 1000;

    public StoryCompletionService(
            StoryCompletionRepository repository, AppUserRepository userRepository,
            TutorStudentRepository tutorStudentRepository, ChildRepository childRepository,
            NotificationPublisher notificationPublisher,
            CompanionChatTurnRepository companionChatTurnRepository,
            LessonRepository lessonRepository, PlaySessionService playSessionService,
            ReportAnalysisStore reportAnalysisStore) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.tutorStudentRepository = tutorStudentRepository;
        this.childRepository = childRepository;
        this.notificationPublisher = notificationPublisher;
        this.companionChatTurnRepository = companionChatTurnRepository;
        this.lessonRepository = lessonRepository;
        this.playSessionService = playSessionService;
        this.reportAnalysisStore = reportAnalysisStore;
    }

    @Transactional
    public StoryCompletionSummary record(CurrentUser caller, RecordStoryCompletionRequest request) {
        if (request.storyId() == null || request.storyId().isBlank()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "storyId가 필요해요.");
        }
        String endStatus = request.endStatus() == null || request.endStatus().isBlank()
                ? "COMPLETED" : request.endStatus().trim().toUpperCase();
        if (!endStatus.equals("COMPLETED") && !endStatus.equals("EXITED")) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "endStatus는 COMPLETED 또는 EXITED예요.");
        }
        AppUser user = userRepository.findById(caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.UNAUTHENTICATED, "로그인이 필요해요.", 401));
        // tutorStudentId는 caller(=선생님) 소유의 학생일 때만 세션 출처로 인정한다 - 남의 학생 id를
        // 끼워 넣어 부모 쪽 공유 리스트에 끼어드는 걸 막는다.
        TutorStudent tutorStudent = request.tutorStudentId() == null
                ? null
                : tutorStudentRepository.findByIdAndTutor_IdAndDeletedAtIsNull(request.tutorStudentId(), caller.userId())
                        .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "학생을 찾을 수 없어요.", 404));
        // childId도 마찬가지 - caller(=부모)가 소유한 아이 프로필일 때만 인정. 선생님의 세션은
        // childId를 보내지 않는 것이 관례이지만, 만약 함께 왔다면 그건 이 부모 계정의 아이가 아니라
        // 404로 응답한다(다른 부모의 아이 id로 리포트를 오염시키는 걸 막는다).
        Child child = request.childId() == null
                ? null
                : childRepository.findByIdAndParent_Id(request.childId(), caller.userId())
                        .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "아이 프로필을 찾을 수 없어요.", 404));
        // 수업에서 시작한 세션 - caller가 소유한 수업이어야 한다. 반 수업이면 참여 학생 전원이 한 기록에 묶인다.
        Lesson lesson = request.lessonId() == null
                ? null
                : lessonRepository.findByIdAndTutor_Id(request.lessonId(), caller.userId())
                        .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "수업을 찾을 수 없어요.", 404));
        // 멱등 저장(053) - 같은 세션(conversationId)의 기록이 이미 있으면 새로 만들지 않고 그것을 돌려준다.
        // 클라이언트 재시도로 기록과 부모 알림이 중복되지 않게 한다.
        if (request.companionConversationId() != null) {
            List<StoryCompletion> existing = repository.findBySessionIdAndUser_IdOrderByCreatedAtAsc(
                    request.companionConversationId(), caller.userId());
            if (!existing.isEmpty()) {
                // 같은 회차를 다시 저장 - 중간에 나갔다가 이어 읽어 끝냈거나 클라이언트가 재시도한 경우. 새로 만들지 않고
                // 최신 값으로 갱신한다(종료 상태는 EXITED → COMPLETED로만). 알림은 처음 저장 때만 갔다.
                StoryCompletion completion = existing.get(0);
                applySessionFields(completion, request, endStatus);
                if (request.outcomes() != null && !request.outcomes().isEmpty()) {
                    completion.setOutcomes(request.outcomes());
                }
                if (request.durationSeconds() != null) {
                    completion.setDurationSeconds(request.durationSeconds());
                }
                Map<String, Object> summary = summarizeCompanionChat(request.companionConversationId());
                if (summary != null) {
                    completion.setCompanionChatSummary(summary);
                }
                completion.setCompletedAt(Instant.now());
                StoryCompletion saved = repository.save(completion);
                reportAnalysisStore.enqueue(saved.getId());
                return StoryCompletionSummary.of(saved);
            }
        }
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

        if (lesson != null && lesson.getClassGroup() == null && participants.isEmpty()) {
            // 반도 학생도 없는 수업의 기록은 선생님 계정에만 남아 어느 부모·기관도 볼 수 없다 - 저장을 거절해
            // 선생님이 학생을 먼저 넣게 한다. 반 수업은 아직 아무도 들어오지 않았어도 반 기록으로 남긴다.
            throw ApiException.contractError(
                    ErrorCode.VALIDATION_FAILED, "이 수업에 참여 학생이 없어요. 수업에 학생을 추가한 뒤 기록해 주세요.", 400);
        }
        if (participants.isEmpty() && (lesson == null || lesson.getClassGroup() == null)) {
            // 가정 세션 - 기록 하나.
            return StoryCompletionSummary.of(
                    saveCompletion(user, lesson, null, child, List.of(), false, request, companionChatSummary, now, endStatus));
        }
        // 반 수업(반을 고른 수업이거나 학생이 여럿)은 한 화면으로 함께 읽은 세션 하나 - 누가 말했는지 모르는
        // 단체 발화를 아이마다 복제하지 않고 한 건으로 남긴다. 개별 수업은 그 학생·아이의 기록이다.
        boolean groupSession = participants.size() > 1 || (lesson != null && lesson.getClassGroup() != null);
        if (groupSession) {
            StoryCompletion completion = saveCompletion(
                    user, lesson, null, null, participants, true, request, companionChatSummary, now, endStatus);
            notifyParentsOfClassSession(participants, completion);
            notifyDirectorOfClassSession(user, completion);
            return StoryCompletionSummary.of(completion);
        }
        TutorStudent participant = participants.get(0);
        // 선생님 세션은 childId를 보내지 않지만, 부모가 초대를 수락하며 연결한 아이 프로필이 학생에
        // 붙어 있으면 그 아이의 기록으로 남긴다.
        Child participantChild = participant.getChild() != null ? participant.getChild() : child;
        StoryCompletion completion = saveCompletion(
                user, lesson, participant, participantChild, participants, false, request, companionChatSummary, now,
                endStatus);
        notifyLinkedParent(participant, completion);
        return StoryCompletionSummary.of(completion);
    }

    private StoryCompletion saveCompletion(
            AppUser user, Lesson lesson, TutorStudent tutorStudent, Child child, List<TutorStudent> participants,
            boolean groupSession, RecordStoryCompletionRequest request, Map<String, Object> companionChatSummary,
            Instant now, String endStatus) {
        // 기관·반 스냅샷: 수업의 반 → 학생의 반 순으로 정한다. 부모의 가정 세션에는 반이 없다.
        ClassGroup classGroup = lesson != null && lesson.getClassGroup() != null
                ? lesson.getClassGroup()
                : participants.isEmpty() ? null : participants.get(0).getClassGroup();
        Organization organization = classGroup != null && classGroup.getOrganization() != null
                ? classGroup.getOrganization()
                : user.getOrganization();
        StoryCompletion saved = repository.save(StoryCompletion.builder()
                .user(user)
                .organization(organization)
                .classGroup(classGroup)
                .className(classGroup == null ? null : classGroup.getName())
                .sessionId(request.companionConversationId())
                .tutorStudent(tutorStudent)
                .child(child)
                .participants(new LinkedHashSet<>(participants))
                .groupSession(groupSession)
                .lesson(lesson)
                .storyId(request.storyId())
                .completedAt(now)
                .durationSeconds(request.durationSeconds())
                .outcomes(request.outcomes() == null ? List.of() : request.outcomes())
                .companionChatSummary(companionChatSummary)
                .createdAt(now)
                .endStatus(endStatus)
                .contentVersion(trimmed(request.contentVersion(), 80))
                .readFromSceneId(trimmed(request.readFromSceneId(), 64))
                .readThroughSceneId(trimmed(request.readThroughSceneId(), 64))
                .build());
        // 회차가 끝나면(중간에 나가도) 관심·생각 분석을 예약한다 - ReportAnalysisWorker가 만든다. 분석 작업 행이 이 기록을
        // 외래 키로 가리키므로, JPA가 미뤄 둔 insert를 먼저 내보낸다(안 그러면 같은 트랜잭션 안에서 FK 위반).
        repository.flush();
        reportAnalysisStore.enqueue(saved.getId());
        return saved;
    }

    private static void applySessionFields(StoryCompletion completion, RecordStoryCompletionRequest request, String endStatus) {
        if ("COMPLETED".equals(endStatus)) {
            completion.setEndStatus("COMPLETED");
        }
        if (request.contentVersion() != null) completion.setContentVersion(trimmed(request.contentVersion(), 80));
        if (completion.getReadFromSceneId() == null && request.readFromSceneId() != null) {
            completion.setReadFromSceneId(trimmed(request.readFromSceneId(), 64));
        }
        if (request.readThroughSceneId() != null) completion.setReadThroughSceneId(trimmed(request.readThroughSceneId(), 64));
    }

    private static String trimmed(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        return text.length() > max ? text.substring(0, max) : text;
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
                tutorStudent.getName() + "의 수업 기록이 도착했어요",
                tutorStudent.getName() + "의 오늘 이야기 세션을 리포트로 확인해 보세요.",
                "/reports/" + completion.getId(),
                "tutor-report:" + completion.getId());
    }

    /** 반 수업 기록은 "우리 반 수업"으로 알린다 - 아이 둘이 같은 반이어도 부모에게는 한 번만(dedupKey). */
    private void notifyParentsOfClassSession(List<TutorStudent> participants, StoryCompletion completion) {
        String className = completion.getClassGroup() != null ? completion.getClassGroup().getName() : null;
        String title = className != null ? className + " 수업 기록이 도착했어요" : "함께 읽은 수업 기록이 도착했어요";
        for (TutorStudent participant : participants) {
            if (participant.getLinkedParentUser() == null) continue;
            notificationPublisher.publish(
                    participant.getLinkedParentUser().getId(),
                    "tutor-report",
                    title,
                    "오늘 우리 반이 어떤 동화를 읽고 어떤 이야기를 나눴는지 확인해 보세요.",
                    "/reports/" + completion.getId(),
                    "tutor-report:" + completion.getId());
        }
    }

    /**
     * 기관 반의 수업 기록은 원장에게도 알린다 - 원장은 반 상세의 최근 리포트에서 바로 본다. 기관이 없는 반(개인
     * 선생님)이나 원장 계정이 없으면 건너뛴다. 원장이 직접 수업을 진행한 경우는 자기 자신에게 알리지 않는다.
     */
    private void notifyDirectorOfClassSession(AppUser teacher, StoryCompletion completion) {
        ClassGroup classGroup = completion.getClassGroup();
        if (classGroup == null || classGroup.getOrganization() == null) return;
        userRepository.findFirstByOrganization_IdAndRoleAndDeletedAtIsNull(classGroup.getOrganization().getId(), Role.DIRECTOR)
                .filter(director -> !director.getId().equals(teacher.getId()))
                .ifPresent(director -> notificationPublisher.publish(
                        director.getId(),
                        "class-report",
                        classGroup.getName() + " 수업 기록이 도착했어요",
                        TeacherName.of(teacher.getDisplayName()) + "이 수업을 마쳤어요. 반 리포트에서 확인해 보세요.",
                        "/organization/classes/" + classGroup.getId(),
                        "class-report:" + completion.getId()));
    }

    /**
     * childId가 주어지면 그 아이 프로필의 완주만, 없으면 caller의 전체 완주.
     * 소유 검증(child가 caller의 것인가)은 목록 조회에도 적용해야 하는데, repository 쿼리 자체가
     * user_id로 스코프되어 있어(다른 부모의 child_id를 넣으면 결과 자체가 비므로) 별도 예외는
     * 던지지 않고 조용히 빈 목록으로 응답한다.
     */
    @Transactional(readOnly = true)
    public List<StoryCompletionSummary> list(CurrentUser caller, UUID childId) {
        // 집에서 읽은 기록만 - 선생님 수업은 /v1/parents/me/tutor-reports("수업 리포트")에서 따로 본다.
        var completions = childId == null
                ? repository.findByUser_IdOrderByCompletedAtDesc(caller.userId())
                : repository.findByUser_IdAndChild_IdOrderByCompletedAtDesc(caller.userId(), childId);
        return completions.stream().map(StoryCompletionSummary::of).toList();
    }

    /** 최근 N회의 전체 outcomes를 함께 반환한다 - 프론트가 여러 회차를 가로지르는 누적 트렌드(반복 접근, 관심 주제)를 계산할 때 쓴다. */
    @Transactional(readOnly = true)
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
        boolean isDirector = isVisibleToDirector(caller, completion);
        if (!isSessionOwner && !isDirector && !isVisibleToParent(id, caller)) {
            throw ApiException.contractError(ErrorCode.NOT_FOUND, "기록을 찾을 수 없어요.", 404);
        }
        boolean staffView = isDirector || (isSessionOwner && !"HOME".equals(completion.sessionKind()));
        List<Map<String, Object>> turns = playSessionService.listTurns(completion.getSessionId());
        boolean turnsAvailable = playSessionService.sessionExists(completion.getSessionId());
        if ("CLASS".equals(completion.sessionKind()) && !staffView) {
            // 반 수업 대화는 어느 아이 말인지 모르고, 다른 아이 말 원문은 부모에게 공개하지 않는다.
            turns = turns.stream().map(StoryCompletionService::withoutChildText).toList();
        }
        StoryCompletionDetail.TeacherNote note = "HOME".equals(completion.sessionKind())
                ? null
                : new StoryCompletionDetail.TeacherNote(
                        staffView ? completion.getTeacherNoteInternal() : null, completion.getTeacherNoteForParents());
        List<StoryCompletionDetail.LinkedChild> linkedChildren = linkedChildren(caller, completion);
        return StoryCompletionDetail.of(
                completion, turns, turnsAvailable, note, reportAnalysisStore.find(completion.getId()), linkedChildren);
    }

    /** 부모 열람 - 참여 학생에 연결됐거나(기존), 그 반에 연결된 부모(Q-39, 연결 전 반 수업 포함). */
    private boolean isVisibleToParent(UUID completionId, CurrentUser caller) {
        return repository.isVisibleToLinkedParent(completionId, caller.userId())
                || repository.isVisibleToClassParent(completionId, caller.userId());
    }

    private static Map<String, Object> withoutChildText(Map<String, Object> turn) {
        if (!"CHILD".equals(turn.get("role"))) return turn;
        Map<String, Object> copy = new LinkedHashMap<>(turn);
        copy.remove("text");
        return copy;
    }

    private List<StoryCompletionDetail.LinkedChild> linkedChildren(CurrentUser caller, StoryCompletion completion) {
        if (caller.role() != Role.PARENT) return List.of();
        if ("HOME".equals(completion.sessionKind())) {
            Child child = completion.getChild();
            return child == null || !completion.getUser().getId().equals(caller.userId())
                    ? List.of()
                    : List.of(new StoryCompletionDetail.LinkedChild(child.getId(), child.getName()));
        }
        return repository.findLinkedChildren(completion.getId(), caller.userId()).stream()
                .map(row -> new StoryCompletionDetail.LinkedChild(UUID.fromString((String) row[0]), (String) row[1]))
                .toList();
    }

    /** 수업 기록의 교사 메모 두 칸 - 기록을 남긴 선생님만 쓴다. 부모에게는 forParents만 간다. */
    @Transactional
    public StoryCompletionDetail.TeacherNote updateTeacherNote(
            CurrentUser caller, UUID id, String internal, String forParents) {
        StoryCompletion completion = repository.findById(id)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "기록을 찾을 수 없어요.", 404));
        if (!completion.getUser().getId().equals(caller.userId()) || "HOME".equals(completion.sessionKind())) {
            throw ApiException.contractError(ErrorCode.NOT_FOUND, "기록을 찾을 수 없어요.", 404);
        }
        if ((internal != null && internal.length() > TEACHER_NOTE_MAX)
                || (forParents != null && forParents.length() > TEACHER_NOTE_MAX)) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "메모는 1000자까지 쓸 수 있어요.");
        }
        completion.setTeacherNoteInternal(blankToNull(internal));
        completion.setTeacherNoteForParents(blankToNull(forParents));
        repository.save(completion);
        return new StoryCompletionDetail.TeacherNote(
                completion.getTeacherNoteInternal(), completion.getTeacherNoteForParents());
    }

    /** 분석 다시 시도 - 기록을 볼 수 있는 사람이면 누구나. */
    @Transactional
    public void retryAnalysis(CurrentUser caller, UUID id) {
        get(caller, id);
        reportAnalysisStore.enqueue(id);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 관리자(DIRECTOR)는 자기 기관의 수업 기록을 개별 리포트까지 열 수 있다(Q-35). 기관은 기록에 남긴 기관
     * 스냅샷(story_completion.organization_id) 또는 기록의 반이 속한 기관으로 정한다 - 아이가 반을 옮겨도 그때
     * 기관의 기록으로 남는다. 가정 세션(보호자가 진행)은 기관 기록이 아니라 열 수 없다.
     */
    static boolean isVisibleToDirector(CurrentUser caller, StoryCompletion completion) {
        if (caller.role() != Role.DIRECTOR || caller.orgId() == null) return false;
        if ("HOME".equals(completion.sessionKind())) return false;
        if (completion.getOrganization() != null) {
            return caller.orgId().equals(completion.getOrganization().getId());
        }
        return completion.getClassGroup() != null
                && completion.getClassGroup().getOrganization() != null
                && caller.orgId().equals(completion.getClassGroup().getOrganization().getId());
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

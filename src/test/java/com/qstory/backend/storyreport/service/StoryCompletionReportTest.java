package com.qstory.backend.storyreport.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.companionchat.repository.CompanionChatTurnRepository;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.parent.child.repository.ChildRepository;
import com.qstory.backend.playsession.service.PlaySessionService;
import com.qstory.backend.reportanalysis.service.ReportAnalysisStore;
import com.qstory.backend.storyreport.dto.RecordStoryCompletionRequest;
import com.qstory.backend.storyreport.dto.StoryCompletionDetail;
import com.qstory.backend.storyreport.entity.StoryCompletion;
import com.qstory.backend.storyreport.repository.StoryCompletionRepository;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Q-39 - 같은 회차 다시 저장(이어 읽기), 반 수업을 부모가 볼 때 숨길 것, 반에 연결된 부모 열람. */
class StoryCompletionReportTest {

    private final StoryCompletionRepository repository = mock(StoryCompletionRepository.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final NotificationPublisher notificationPublisher = mock(NotificationPublisher.class);
    private final PlaySessionService playSessionService = mock(PlaySessionService.class);
    private final ReportAnalysisStore reportAnalysisStore = mock(ReportAnalysisStore.class);
    private final StoryCompletionService service = new StoryCompletionService(
            repository, userRepository, mock(TutorStudentRepository.class), mock(ChildRepository.class),
            notificationPublisher, mock(CompanionChatTurnRepository.class), mock(LessonRepository.class),
            playSessionService, reportAnalysisStore);

    private final AppUser parent = AppUser.builder().id(UUID.randomUUID()).role(Role.PARENT).displayName("보호자").build();
    private final CurrentUser parentCaller = new CurrentUser(parent.getId(), Role.PARENT, null);
    private final AppUser tutor = AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).displayName("김선생").build();

    @org.junit.jupiter.api.BeforeEach
    void ownSessionsAreLinked() {
        // 기본은 호출자 자신의 회차 - linkForCompletion은 받은 회차 id를 그대로 돌려준다.
        when(playSessionService.linkForCompletion(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void savingTheSameSessionAgainUpdatesInsteadOfDuplicating() {
        UUID sessionId = UUID.randomUUID();
        StoryCompletion exited = StoryCompletion.builder().id(UUID.randomUUID()).user(parent).sessionId(sessionId)
                .storyId("HG").completedAt(Instant.EPOCH).outcomes(List.of()).endStatus("EXITED")
                .readFromSceneId("HG-F01").readThroughSceneId("HG-F06").build();
        when(userRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(repository.findBySessionIdAndUser_IdOrderByCreatedAtAsc(sessionId, parent.getId())).thenReturn(List.of(exited));
        when(repository.save(any(StoryCompletion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var summary = service.record(parentCaller, new RecordStoryCompletionRequest(
                "HG", 900, List.of(Map.of("anchorId", "HG-Q-C")), null, null, sessionId, null,
                "COMPLETED", "q31-dialogue-v1", "HG-F05", "HG-F10"));

        assertEquals("COMPLETED", summary.endStatus());
        assertEquals("HG-F01", exited.getReadFromSceneId(), "처음 장면은 처음 값 그대로");
        assertEquals("HG-F10", exited.getReadThroughSceneId());
        assertEquals(1, exited.getOutcomes().size());
        verify(reportAnalysisStore).enqueue(exited.getId());
        verify(notificationPublisher, never()).publish(any(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void newRecordIsFlushedBeforeTheAnalysisJobPointsAtIt() {
        when(userRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(repository.save(any(StoryCompletion.class))).thenAnswer(invocation -> {
            StoryCompletion saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        service.record(parentCaller, new RecordStoryCompletionRequest("HG", 60, List.of(), null, null, UUID.randomUUID(), null));

        // report_analysis가 story_completion을 외래 키로 가리키므로, 미뤄 둔 insert가 먼저 나가야 한다(로컬 실행에서 500으로 발견).
        var order = org.mockito.Mockito.inOrder(repository, reportAnalysisStore);
        order.verify(repository).flush();
        order.verify(reportAnalysisStore).enqueue(any());
    }

    @Test
    void completedSessionNeverGoesBackToExited() {
        UUID sessionId = UUID.randomUUID();
        StoryCompletion done = StoryCompletion.builder().id(UUID.randomUUID()).user(parent).sessionId(sessionId)
                .storyId("HG").completedAt(Instant.EPOCH).outcomes(List.of()).endStatus("COMPLETED").build();
        when(userRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(repository.findBySessionIdAndUser_IdOrderByCreatedAtAsc(sessionId, parent.getId())).thenReturn(List.of(done));
        when(repository.save(any(StoryCompletion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var summary = service.record(parentCaller, new RecordStoryCompletionRequest(
                "HG", 10, List.of(), null, null, sessionId, null, "EXITED", null, null, null));

        assertEquals("COMPLETED", summary.endStatus());
    }

    @Test
    void parentSeesClassLessonWithoutChildWordsOrInternalNote() {
        StoryCompletion lesson = classRecord();
        when(repository.findById(lesson.getId())).thenReturn(Optional.of(lesson));
        when(repository.isVisibleToLinkedParent(lesson.getId(), parent.getId())).thenReturn(false);
        when(repository.isVisibleToClassParent(lesson.getId(), parent.getId())).thenReturn(true);
        when(playSessionService.isOwnedBy(lesson.getSessionId(), tutor.getId())).thenReturn(true);
        when(playSessionService.listTurns(lesson.getSessionId(), tutor.getId())).thenReturn(List.of(
                Map.of("seq", 1, "role", "CHARACTER", "text", "저 새를 보니 궁금한 게 있어?"),
                Map.of("seq", 2, "role", "CHILD", "speaker", "TEACHER_RELAY", "text", "새한테 말 걸어보자")));
        when(repository.findLinkedChildren(lesson.getId(), parent.getId()))
                .thenReturn(List.<Object[]>of(new Object[] {UUID.randomUUID().toString(), "서아"}));

        StoryCompletionDetail detail = service.get(parentCaller, lesson.getId());

        assertTrue(detail.turnsAvailable());
        assertEquals("저 새를 보니 궁금한 게 있어?", detail.turns().get(0).get("text"));
        assertFalse(detail.turns().get(1).containsKey("text"), "반 수업 아이 말 원문은 부모에게 비공개");
        assertNull(detail.teacherNote().internal());
        assertEquals("오늘은 낯선 곳에 대해 이야기했어요.", detail.teacherNote().forParents());
        assertEquals("서아", detail.linkedChildren().get(0).name());
        // 보안 점검 H1: 부모에게는 회차 id 전체 대신 화면용 6자 코드만.
        assertNull(detail.sessionId());
        assertEquals(lesson.getSessionId().toString().replace("-", "").substring(0, 6).toUpperCase(), detail.sessionCode());
    }

    @Test
    void teacherSeesEverythingOnTheirOwnLesson() {
        StoryCompletion lesson = classRecord();
        when(repository.findById(lesson.getId())).thenReturn(Optional.of(lesson));
        when(playSessionService.listTurns(lesson.getSessionId(), tutor.getId())).thenReturn(List.of(
                Map.of("seq", 2, "role", "CHILD", "speaker", "TEACHER_RELAY", "text", "새한테 말 걸어보자")));

        StoryCompletionDetail detail = service.get(new CurrentUser(tutor.getId(), Role.TUTOR, null), lesson.getId());

        assertEquals("새한테 말 걸어보자", detail.turns().get(0).get("text"));
        assertEquals("B에서 시간이 걸림", detail.teacherNote().internal());
        assertEquals(lesson.getSessionId(), detail.sessionId());
    }

    /**
     * 보안 점검 H1: 다른 계정의 회차 id로 기록을 남겨도 그 회차는 기록에 이어지지 않는다 - 회차 id만 알아낸 사람이 자기 기록
     * 상세로 남의 대화 원문을 읽던 문제.
     */
    @Test
    void foreignSessionIdIsNotLinkedToTheRecord() {
        UUID victimSession = UUID.randomUUID();
        when(userRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(playSessionService.linkForCompletion(victimSession, parent.getId(), null)).thenReturn(null);
        org.mockito.ArgumentCaptor<StoryCompletion> saved = org.mockito.ArgumentCaptor.forClass(StoryCompletion.class);
        when(repository.save(saved.capture())).thenAnswer(invocation -> {
            StoryCompletion completion = invocation.getArgument(0);
            completion.setId(UUID.randomUUID());
            return completion;
        });

        service.record(parentCaller, new RecordStoryCompletionRequest("HG", 60, List.of(), null, null, victimSession, null));

        assertNull(saved.getValue().getSessionId(), "남의 회차는 잇지 않는다");
        verify(repository, never()).findBySessionIdAndUser_IdOrderByCreatedAtAsc(any(), any());
    }

    /** 보안 점검 H1: 기록에 적힌 회차라도 기록 주인의 회차가 아니면 대화를 붙이지 않는다(예전에 저장된 기록 포함). */
    @Test
    void turnsAreReadOnlyFromTheRecordOwnersSession() {
        UUID foreignSession = UUID.randomUUID();
        StoryCompletion home = StoryCompletion.builder().id(UUID.randomUUID()).user(parent).sessionId(foreignSession)
                .storyId("HG").completedAt(Instant.now()).outcomes(List.of()).build();
        when(repository.findById(home.getId())).thenReturn(Optional.of(home));
        // 서비스는 늘 기록 주인(parent)으로 묻는다 - 남의 회차면 PlaySessionService가 빈 목록·false를 준다.
        when(playSessionService.listTurns(foreignSession, parent.getId())).thenReturn(List.of());
        when(playSessionService.isOwnedBy(foreignSession, parent.getId())).thenReturn(false);

        StoryCompletionDetail detail = service.get(parentCaller, home.getId());

        assertTrue(detail.turns().isEmpty());
        assertFalse(detail.turnsAvailable());
        verify(playSessionService, never()).listTurns(eq(foreignSession), org.mockito.ArgumentMatchers.argThat(
                id -> !parent.getId().equals(id)));
    }

    @Test
    void onlyTheOwnerTeacherWritesNotes() {
        StoryCompletion lesson = classRecord();
        when(repository.findById(lesson.getId())).thenReturn(Optional.of(lesson));
        when(repository.save(any(StoryCompletion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var note = service.updateTeacherNote(new CurrentUser(tutor.getId(), Role.TUTOR, null), lesson.getId(), " 메모 ", "");
        assertEquals("메모", note.internal());
        assertNull(note.forParents());

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                com.qstory.backend.common.error.ApiException.class,
                () -> service.updateTeacherNote(parentCaller, lesson.getId(), "x", "y"));
        assertEquals(404, error.statusCode());
    }

    private StoryCompletion classRecord() {
        return StoryCompletion.builder().id(UUID.randomUUID()).user(tutor).sessionId(UUID.randomUUID())
                .classGroup(ClassGroup.builder().id(UUID.randomUUID()).name("해바라기반").build())
                .groupSession(true).storyId("HG").completedAt(Instant.now()).outcomes(List.of())
                .teacherNoteInternal("B에서 시간이 걸림").teacherNoteForParents("오늘은 낯선 곳에 대해 이야기했어요.").build();
    }
}

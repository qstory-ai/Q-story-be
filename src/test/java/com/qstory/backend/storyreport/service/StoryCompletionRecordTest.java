package com.qstory.backend.storyreport.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.companionchat.repository.CompanionChatTurnRepository;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.Role;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.parent.child.repository.ChildRepository;
import com.qstory.backend.storyreport.dto.RecordStoryCompletionRequest;
import com.qstory.backend.storyreport.dto.StoryCompletionSummary;
import com.qstory.backend.storyreport.entity.StoryCompletion;
import com.qstory.backend.storyreport.repository.StoryCompletionRepository;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.LessonStatus;
import com.qstory.backend.tutor.lesson.entity.Lesson;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 반 수업 한 번은 반 단위 기록 한 건 - 아이마다 복제하지 않고, 연결된 부모에게는 반 수업으로 알린다. */
class StoryCompletionRecordTest {

    private final StoryCompletionRepository repository = mock(StoryCompletionRepository.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final TutorStudentRepository tutorStudentRepository = mock(TutorStudentRepository.class);
    private final NotificationPublisher notificationPublisher = mock(NotificationPublisher.class);
    private final LessonRepository lessonRepository = mock(LessonRepository.class);
    private final StoryCompletionService service = new StoryCompletionService(
            repository, userRepository, tutorStudentRepository, mock(ChildRepository.class), notificationPublisher,
            mock(CompanionChatTurnRepository.class), lessonRepository);

    private final AppUser tutor = AppUser.builder().id(UUID.randomUUID()).displayName("김선생").build();
    private final CurrentUser caller = new CurrentUser(tutor.getId(), Role.TUTOR, null);

    @BeforeEach
    void setUp() {
        when(userRepository.findById(tutor.getId())).thenReturn(Optional.of(tutor));
        when(repository.save(any(StoryCompletion.class))).thenAnswer(invocation -> {
            StoryCompletion saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
    }

    @Test
    void classLessonIsOneRecordForAllParticipants() {
        ClassGroup sunClass = ClassGroup.builder().id(UUID.randomUUID()).name("햇님반").build();
        TutorStudent minseo = student("민서", parent());
        TutorStudent jiho = student("지호", parent());
        TutorStudent pending = student("하윤", null);
        Lesson lesson = lesson(sunClass, minseo, jiho, pending);

        StoryCompletionSummary summary = service.record(caller, request(lesson.getId()));

        ArgumentCaptor<StoryCompletion> saved = ArgumentCaptor.forClass(StoryCompletion.class);
        verify(repository, times(1)).save(saved.capture());
        StoryCompletion completion = saved.getValue();
        assertTrue(completion.isGroupSession());
        assertNull(completion.getTutorStudent());
        assertNull(completion.getChild());
        assertEquals(3, completion.getParticipants().size());
        assertSame(sunClass, completion.getClassGroup());
        assertEquals("CLASS", summary.sessionKind());
        // 연결된 부모 둘에게만, 같은 기록을 가리키는 반 수업 알림.
        verify(notificationPublisher, times(2)).publish(
                any(UUID.class), eq("tutor-report"), eq("햇님반 수업 기록이 도착했어요"), anyString(),
                eq("/reports/" + completion.getId()), eq("tutor-report:" + completion.getId()));
    }

    @Test
    void classLessonIncludesClassmatesWhoJoinedLaterAndSavesEvenWhenEmpty() {
        ClassGroup moonClass = ClassGroup.builder().id(UUID.randomUUID()).name("달님반").build();
        Lesson emptyLesson = lesson(moonClass);

        StoryCompletionSummary empty = service.record(caller, request(emptyLesson.getId()));
        assertEquals("CLASS", empty.sessionKind());
        assertEquals(0, empty.participantCount());

        TutorStudent joinedByLink = student("하준", parent());
        when(tutorStudentRepository.findByClassGroup_IdAndTutor_IdAndDeletedAtIsNullOrderByCreatedAtAsc(
                moonClass.getId(), tutor.getId())).thenReturn(List.of(joinedByLink));
        StoryCompletionSummary withJoiner = service.record(caller, request(emptyLesson.getId()));
        assertEquals(1, withJoiner.participantCount());
    }

    @Test
    void individualLessonStaysTheStudentsRecord() {
        TutorStudent minseo = student("민서", parent());
        Lesson lesson = lesson(null, minseo);

        StoryCompletionSummary summary = service.record(caller, request(lesson.getId()));

        ArgumentCaptor<StoryCompletion> saved = ArgumentCaptor.forClass(StoryCompletion.class);
        verify(repository).save(saved.capture());
        assertFalse(saved.getValue().isGroupSession());
        assertSame(minseo, saved.getValue().getTutorStudent());
        assertEquals("TUTOR", summary.sessionKind());
        assertEquals(1, summary.participantCount());
    }

    private AppUser parent() {
        return AppUser.builder().id(UUID.randomUUID()).displayName("부모").build();
    }

    private TutorStudent student(String name, AppUser linkedParent) {
        return TutorStudent.builder().id(UUID.randomUUID()).name(name).linkedParentUser(linkedParent).build();
    }

    private Lesson lesson(ClassGroup classGroup, TutorStudent... students) {
        Lesson lesson = Lesson.builder()
                .id(UUID.randomUUID())
                .tutor(tutor)
                .name("오늘 수업")
                .status(LessonStatus.IN_PROGRESS)
                .classGroup(classGroup)
                .students(new LinkedHashSet<>(List.of(students)))
                .build();
        when(lessonRepository.findByIdAndTutor_Id(lesson.getId(), tutor.getId())).thenReturn(Optional.of(lesson));
        return lesson;
    }

    private static RecordStoryCompletionRequest request(UUID lessonId) {
        return new RecordStoryCompletionRequest("HG", 600, List.of(), null, null, null, lessonId);
    }
}

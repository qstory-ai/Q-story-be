package com.qstory.backend.tutor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.storyreport.dto.StoryCompletionSummary;
import com.qstory.backend.storyreport.entity.StoryCompletion;
import com.qstory.backend.storyreport.repository.StoryCompletionRepository;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 담임이 바뀌어 넘어온 학생 - 새 담임의 학생 리포트에는 자기가 진행한 기록만 보인다(Q-35). */
class TutorReportServiceStudentTest {

    private final StoryCompletionRepository completionRepository = mock(StoryCompletionRepository.class);
    private final TutorStudentRepository studentRepository = mock(TutorStudentRepository.class);
    private final TutorReportService service =
            new TutorReportService(completionRepository, studentRepository, mock(LessonRepository.class));

    @Test
    void newHomeroomTutorSeesOnlySessionsTheyRan() {
        AppUser previous = AppUser.builder().id(UUID.randomUUID()).displayName("김선생").build();
        AppUser current = AppUser.builder().id(UUID.randomUUID()).displayName("이선생").build();
        TutorStudent student = TutorStudent.builder().id(UUID.randomUUID()).tutor(current).name("민서").ageBand("5세").build();
        when(studentRepository.findByIdAndTutor_IdAndDeletedAtIsNull(student.getId(), current.getId()))
                .thenReturn(Optional.of(student));
        StoryCompletion before = completion(previous);
        StoryCompletion after = completion(current);
        when(completionRepository.findByParticipant(student.getId())).thenReturn(List.of(after, before));

        List<StoryCompletionSummary> result =
                service.listStudentCompletions(new CurrentUser(current.getId(), Role.TUTOR, null), student.getId());

        assertEquals(List.of(after.getId()), result.stream().map(StoryCompletionSummary::id).toList());
    }

    private static StoryCompletion completion(AppUser tutor) {
        return StoryCompletion.builder().id(UUID.randomUUID()).user(tutor).groupSession(true).storyId("HG")
                .completedAt(Instant.now()).build();
    }
}

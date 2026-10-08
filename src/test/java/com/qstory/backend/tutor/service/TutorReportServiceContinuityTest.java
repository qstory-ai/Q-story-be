package com.qstory.backend.tutor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.Organization;
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

/** 076: 원장이 옮겨 온 학생 - 새 담임의 학생 화면에 지난 반(같은 기관) 기록이 이어지고, 반 이름은 그때 이름. */
class TutorReportServiceContinuityTest {

    private final StoryCompletionRepository completionRepository = mock(StoryCompletionRepository.class);
    private final TutorStudentRepository studentRepository = mock(TutorStudentRepository.class);
    private final TutorReportService service =
            new TutorReportService(completionRepository, studentRepository, mock(LessonRepository.class));

    @Test
    void movedStudentsEarlierClassRecordsAreVisibleToTheNewHomeroom() {
        Organization organization = Organization.builder().id(UUID.randomUUID()).build();
        Organization other = Organization.builder().id(UUID.randomUUID()).build();
        AppUser kim = AppUser.builder().id(UUID.randomUUID()).displayName("김선생").build();
        AppUser lee = AppUser.builder().id(UUID.randomUUID()).displayName("이선생").build();
        ClassGroup sun = ClassGroup.builder().id(UUID.randomUUID()).organization(organization).name("해님반").build();
        ClassGroup star = ClassGroup.builder().id(UUID.randomUUID()).organization(organization).name("별님반").build();
        ClassGroup foreign = ClassGroup.builder().id(UUID.randomUUID()).organization(other).name("남의반").build();
        TutorStudent minseo = TutorStudent.builder().id(UUID.randomUUID()).tutor(lee).classGroup(star).name("민서")
                .ageBand("5세").build();
        when(studentRepository.findByIdAndTutor_IdAndDeletedAtIsNull(minseo.getId(), lee.getId()))
                .thenReturn(Optional.of(minseo));
        StoryCompletion own = completion(lee, star, organization, "별님반");
        StoryCompletion earlierClass = completion(kim, sun, organization, "햇님반");
        StoryCompletion previousHomeroomSameClass = completion(kim, star, organization, "별님반");
        StoryCompletion otherOrganization = completion(kim, foreign, other, "남의반");
        when(completionRepository.findByParticipant(minseo.getId()))
                .thenReturn(List.of(own, earlierClass, previousHomeroomSameClass, otherOrganization));

        List<StoryCompletionSummary> result =
                service.listStudentCompletions(new CurrentUser(lee.getId(), Role.TUTOR, null), minseo.getId());

        assertEquals(List.of(own.getId(), earlierClass.getId()), result.stream().map(StoryCompletionSummary::id).toList());
        assertEquals("햇님반", result.get(1).className(), "반 이름을 바꿔도 그때 이름");
    }

    private static StoryCompletion completion(AppUser tutor, ClassGroup classGroup, Organization organization, String name) {
        return StoryCompletion.builder().id(UUID.randomUUID()).user(tutor).classGroup(classGroup)
                .organization(organization).className(name).groupSession(true).storyId("HG")
                .completedAt(Instant.now()).build();
    }
}

package com.qstory.backend.storyreport.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.companionchat.repository.CompanionChatTurnRepository;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.parent.child.repository.ChildRepository;
import com.qstory.backend.storyreport.entity.StoryCompletion;
import com.qstory.backend.storyreport.repository.StoryCompletionRepository;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 관리자 개별 리포트 열람(Q-35) - 자기 기관의 수업 기록만, 가정 기록은 열 수 없다. */
class StoryCompletionDirectorAccessTest {

    private final StoryCompletionRepository repository = mock(StoryCompletionRepository.class);
    private final StoryCompletionService service = new StoryCompletionService(
            repository, mock(AppUserRepository.class), mock(TutorStudentRepository.class), mock(ChildRepository.class),
            mock(NotificationPublisher.class), mock(CompanionChatTurnRepository.class), mock(LessonRepository.class));

    private final Organization organization = Organization.builder().id(UUID.randomUUID()).name("햇살유치원").build();
    private final AppUser tutor = AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).displayName("김선생").build();
    private final CurrentUser director = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, organization.getId());

    @Test
    void directorOpensClassLessonReportOfOwnOrganization() {
        StoryCompletion completion = classSession(organization, null);
        when(repository.findById(completion.getId())).thenReturn(Optional.of(completion));

        assertEquals(completion.getId(), service.get(director, completion.getId()).id());
    }

    @Test
    void directorOfAnotherOrganizationGets404() {
        StoryCompletion completion = classSession(organization, null);
        when(repository.findById(completion.getId())).thenReturn(Optional.of(completion));
        when(repository.isVisibleToLinkedParent(any(), any())).thenReturn(false);
        CurrentUser other = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, UUID.randomUUID());

        ApiException error = assertThrows(ApiException.class, () -> service.get(other, completion.getId()));
        assertEquals(404, error.statusCode());
    }

    @Test
    void organizationFallsBackToTheClassWhenNoSnapshot() {
        ClassGroup classGroup = ClassGroup.builder().id(UUID.randomUUID()).organization(organization).build();
        assertTrue(StoryCompletionService.isVisibleToDirector(director, classSession(null, classGroup)));
        CurrentUser other = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, UUID.randomUUID());
        assertFalse(StoryCompletionService.isVisibleToDirector(other, classSession(null, classGroup)));
    }

    @Test
    void homeSessionsAndNonDirectorsAreNeverOpenedThisWay() {
        StoryCompletion home = StoryCompletion.builder().id(UUID.randomUUID()).user(tutor).organization(organization)
                .storyId("HG").completedAt(Instant.now()).outcomes(List.of()).build();
        assertFalse(StoryCompletionService.isVisibleToDirector(director, home));

        CurrentUser sameOrgTutor = new CurrentUser(UUID.randomUUID(), Role.TUTOR, organization.getId());
        assertFalse(StoryCompletionService.isVisibleToDirector(sameOrgTutor, classSession(organization, null)));
        CurrentUser directorWithoutOrg = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, null);
        assertFalse(StoryCompletionService.isVisibleToDirector(directorWithoutOrg, classSession(organization, null)));
    }

    private StoryCompletion classSession(Organization snapshot, ClassGroup classGroup) {
        return StoryCompletion.builder().id(UUID.randomUUID()).user(tutor).organization(snapshot).classGroup(classGroup)
                .groupSession(true).storyId("HG").completedAt(Instant.now()).outcomes(List.of()).build();
    }
}

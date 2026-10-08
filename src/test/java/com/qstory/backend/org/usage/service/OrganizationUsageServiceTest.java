package com.qstory.backend.org.usage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.repository.OrganizationRepository;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.org.usage.dto.OrganizationUsageResponse;
import com.qstory.backend.storyreport.entity.StoryCompletion;
import com.qstory.backend.storyreport.repository.StoryCompletionRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 최근 활동에 어느 반의 기록인지(className)가 함께 나간다 - 가정 기록 등 반이 없으면 null. */
class OrganizationUsageServiceTest {

    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final StoryCompletionRepository completionRepository = mock(StoryCompletionRepository.class);
    private final OrganizationUsageService service = new OrganizationUsageService(
            organizationRepository, mock(OrganizationTutorRepository.class), mock(ClassGroupRepository.class),
            mock(TutorStudentRepository.class), completionRepository);

    @Test
    void recentActivityCarriesTheClassName() {
        Organization organization = Organization.builder().id(UUID.randomUUID()).name("햇살유치원").build();
        CurrentUser director = new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, organization.getId());
        when(organizationRepository.findById(organization.getId())).thenReturn(Optional.of(organization));
        AppUser tutor = AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).displayName("김선생").build();
        ClassGroup classGroup = ClassGroup.builder().id(UUID.randomUUID()).organization(organization).name("햇님반").build();
        StoryCompletion inClass = StoryCompletion.builder().id(UUID.randomUUID()).user(tutor).classGroup(classGroup)
                .groupSession(true).storyId("HG").completedAt(Instant.now()).build();
        StoryCompletion noClass = StoryCompletion.builder().id(UUID.randomUUID()).user(tutor)
                .storyId("HG").completedAt(Instant.now()).build();
        when(completionRepository.findByOrganization_IdOrderByCompletedAtDesc(eq(organization.getId()), any()))
                .thenReturn(List.of(inClass, noClass));

        List<OrganizationUsageResponse.RecentActivity> recent =
                service.read(director, organization.getId()).recentActivity();

        assertEquals("햇님반", recent.get(0).className());
        assertNull(recent.get(1).className());
    }
}

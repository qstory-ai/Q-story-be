package com.qstory.backend.tutor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.dto.ClassResponse;
import com.qstory.backend.org.dto.PastClassResponse;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.ClassHomeroomHistory;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.service.ClassHomeroomHistoryService;
import com.qstory.backend.org.tutor.entity.OrganizationTutor;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.org.util.JoinCodeGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 076: 지금 맡은 반 목록은 지난 반을 빼고, 예전에 맡았던 반은 /past로(기관 소속일 때만). 지난 반에는 수업을 만들지 않는다. */
class TutorClassServicePastTest {

    private final ClassGroupRepository classGroupRepository = mock(ClassGroupRepository.class);
    private final OrganizationTutorRepository organizationTutorRepository = mock(OrganizationTutorRepository.class);
    private final ClassHomeroomHistoryService homeroomHistoryService = mock(ClassHomeroomHistoryService.class);
    private final TutorClassService service = new TutorClassService(
            classGroupRepository, organizationTutorRepository, mock(AppUserRepository.class), mock(JoinCodeGenerator.class),
            homeroomHistoryService);

    private final Organization organization = Organization.builder().id(UUID.randomUUID()).name("햇살유치원").build();
    private final Organization leftOrganization = Organization.builder().id(UUID.randomUUID()).name("떠난유치원").build();
    private final AppUser kim = AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).displayName("김선생").build();
    private final AppUser lee = AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).displayName("이선생").build();
    private final CurrentUser caller = new CurrentUser(kim.getId(), Role.TUTOR, null);

    @Test
    void currentListHidesArchivedClasses() {
        ClassGroup active = classGroup(organization, "햇님반", kim, null);
        ClassGroup archived = classGroup(organization, "지난반", kim, Instant.now());
        when(classGroupRepository.findByTutor_IdOrderByCreatedAtAsc(kim.getId())).thenReturn(List.of(active, archived));

        assertEquals(List.of(active.getId()), service.listVisible(caller).stream().map(ClassResponse::id).toList());
    }

    @Test
    void lessonsCannotTargetArchivedClass() {
        ClassGroup archived = classGroup(organization, "지난반", kim, Instant.now());
        when(classGroupRepository.findByIdAndTutor_Id(archived.getId(), kim.getId())).thenReturn(Optional.of(archived));

        ApiException error = assertThrows(ApiException.class, () -> service.requireVisible(caller, archived.getId()));
        assertEquals(409, error.statusCode());
        assertEquals(ErrorCode.CLASS_ARCHIVED, error.code());
    }

    @Test
    void pastListsClassesHandedOverAndArchivedOnesWhileStillInTheOrganization() {
        ClassGroup handedOver = classGroup(organization, "별님반", lee, null);
        ClassGroup archivedStillMine = classGroup(organization, "지난반", kim, Instant.parse("2027-02-28T00:00:00Z"));
        ClassGroup currentActive = classGroup(organization, "햇님반", kim, null);
        ClassGroup ofLeftOrganization = classGroup(leftOrganization, "떠난반", lee, null);
        ClassGroup personal = classGroup(null, "개인반", lee, null);
        Instant t1 = Instant.parse("2026-03-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-06-01T00:00:00Z");
        Instant t3 = Instant.parse("2026-09-01T00:00:00Z");
        when(homeroomHistoryService.listByTutor(kim.getId())).thenReturn(List.of(
                entry(handedOver, t1, t2), entry(handedOver, t2, t3),
                entry(archivedStillMine, t1, null), entry(currentActive, t1, null),
                entry(ofLeftOrganization, t1, t2), entry(personal, t1, t2)));
        when(organizationTutorRepository.findByOrganization_IdAndTutor_Id(organization.getId(), kim.getId()))
                .thenReturn(Optional.of(OrganizationTutor.builder().organization(organization).tutor(kim).build()));
        when(organizationTutorRepository.findByOrganization_IdAndTutor_Id(leftOrganization.getId(), kim.getId()))
                .thenReturn(Optional.empty());

        List<PastClassResponse> past = service.listPast(caller);

        assertEquals(List.of("지난반", "별님반"), past.stream().map(PastClassResponse::name).toList());
        assertNull(past.get(0).ledUntil(), "보관된 반을 아직 맡고 있으면 ledUntil이 없다");
        assertEquals(archivedStillMine.getArchivedAt(), past.get(0).archivedAt());
        assertEquals(t1, past.get(1).ledFrom());
        assertEquals(t3, past.get(1).ledUntil());
        assertEquals("햇살유치원", past.get(1).organizationName());
    }

    private static ClassGroup classGroup(Organization organization, String name, AppUser tutor, Instant archivedAt) {
        return ClassGroup.builder().id(UUID.randomUUID()).organization(organization).name(name).tutor(tutor)
                .joinCode(name).createdAt(Instant.now()).archivedAt(archivedAt).build();
    }

    private ClassHomeroomHistory entry(ClassGroup classGroup, Instant startedAt, Instant endedAt) {
        return ClassHomeroomHistory.builder().id(UUID.randomUUID()).classGroup(classGroup).tutor(kim)
                .startedAt(startedAt).endedAt(endedAt).build();
    }
}

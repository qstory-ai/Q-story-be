package com.qstory.backend.org.tutor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.tutor.entity.OrganizationTutor;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.tutor.TutorStudentStatus;
import com.qstory.backend.tutor.dto.TutorStudentResponse;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.entity.Lesson;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 보안 점검 M8 - 원장은 소속 선생님의 학생·수업 중 자기 기관 반 것만 본다(다른 기관·개인 반 제외). */
class OrganizationTutorInsightScopeTest {

    private final Organization mine = Organization.builder().id(UUID.randomUUID()).name("햇살유치원").build();
    private final Organization other = Organization.builder().id(UUID.randomUUID()).name("달빛유치원").build();
    private final ClassGroup myClass = ClassGroup.builder().id(UUID.randomUUID()).name("햇살반").organization(mine).build();
    private final ClassGroup otherClass = ClassGroup.builder().id(UUID.randomUUID()).name("달빛반").organization(other).build();
    private final ClassGroup personalClass = ClassGroup.builder().id(UUID.randomUUID()).name("개인반").build();

    private TutorStudent student(String name, ClassGroup classGroup) {
        return TutorStudent.builder().id(UUID.randomUUID()).name(name).classGroup(classGroup)
                .status(TutorStudentStatus.CONFIRMED).createdAt(Instant.now()).build();
    }

    @Test
    void studentsOfOtherOrganizationsOrPersonalClassesAreHidden() {
        UUID tutorId = UUID.randomUUID();
        TutorStudentRepository students = mock(TutorStudentRepository.class);
        OrganizationTutorRepository links = mock(OrganizationTutorRepository.class);
        when(links.findByOrganization_IdAndTutor_Id(mine.getId(), tutorId))
                .thenReturn(Optional.of(OrganizationTutor.builder().build()));
        when(students.findByTutor_IdAndDeletedAtIsNullOrderByCreatedAtAsc(tutorId)).thenReturn(List.of(
                student("민서", myClass), student("지호", otherClass), student("하윤", personalClass), student("서아", null)));
        OrganizationTutorInsightService service =
                new OrganizationTutorInsightService(links, students, mock(LessonRepository.class));

        List<TutorStudentResponse> visible =
                service.listStudents(new CurrentUser(UUID.randomUUID(), Role.DIRECTOR, mine.getId()), mine.getId(), tutorId);

        assertThat(visible).extracting(TutorStudentResponse::name).containsExactly("민서");
    }

    @Test
    void lessonsAreScopedByClassOrByTheirStudents() {
        Lesson classLesson = Lesson.builder().classGroup(myClass).build();
        Lesson otherOrgLesson = Lesson.builder().classGroup(otherClass).build();
        Lesson individualWithMyStudent = Lesson.builder()
                .students(new LinkedHashSet<>(List.of(student("민서", myClass)))).build();
        Lesson individualWithOtherStudent = Lesson.builder()
                .students(new LinkedHashSet<>(List.of(student("지호", otherClass)))).build();

        assertThat(OrganizationTutorInsightService.inOrganization(classLesson, mine.getId())).isTrue();
        assertThat(OrganizationTutorInsightService.inOrganization(otherOrgLesson, mine.getId())).isFalse();
        assertThat(OrganizationTutorInsightService.inOrganization(individualWithMyStudent, mine.getId())).isTrue();
        assertThat(OrganizationTutorInsightService.inOrganization(individualWithOtherStudent, mine.getId())).isFalse();
    }
}

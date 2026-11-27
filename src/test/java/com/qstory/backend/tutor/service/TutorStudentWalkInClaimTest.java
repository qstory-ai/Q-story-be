package com.qstory.backend.tutor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.service.StudentClassHistoryService;
import com.qstory.backend.parent.child.entity.Child;
import com.qstory.backend.parent.child.repository.ChildRepository;
import com.qstory.backend.tutor.TutorStudentStatus;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 체험 모드(104) - 선생님이 현장에서 이름만 올린 학생과, 아이가 다 읽은 태블릿의 QR 코드로 보호자가 그 학생에 잇는 경로.
 * 코드로 이으면 연결 시각이 학생을 올린 시각이 되어 연결 전 체험 기록도 보인다(StoryCompletionRepository LINKED_PARENT_SEES_RECORD).
 */
class TutorStudentWalkInClaimTest {

    private final TutorStudentRepository students = mock(TutorStudentRepository.class);
    private final NotificationPublisher notifications = mock(NotificationPublisher.class);
    private final ChildRepository children = mock(ChildRepository.class);
    private final TutorStudentService service = new TutorStudentService(
            students, mock(AppUserRepository.class), notifications, children, mock(LessonRepository.class),
            mock(StudentClassHistoryService.class));

    private final AppUser tutor = AppUser.builder().id(UUID.randomUUID()).role(Role.TUTOR).displayName("도서관 선생님").build();
    private final AppUser parent = AppUser.builder().id(UUID.randomUUID()).role(Role.PARENT).displayName("보호자").build();
    private final ClassGroup event =
            ClassGroup.builder().id(UUID.randomUUID()).name("송파도서관 체험").tutor(tutor).build();

    @BeforeEach
    void setUp() {
        when(students.saveAndFlush(any(TutorStudent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(children.save(any(Child.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void walkInStudentGetsAClaimCodeAndNoParent() {
        TutorStudent student = service.addWalkInStudent(event, "  김하늘 ");

        assertThat(student.getName()).isEqualTo("김하늘");
        assertThat(student.getStatus()).isEqualTo(TutorStudentStatus.PENDING_PARENT);
        assertThat(student.getLinkedParentUser()).isNull();
        assertThat(student.getTutor()).isEqualTo(tutor);
        assertThat(student.getParentClaimCode()).matches("[A-HJKMNP-Z2-9]{10}");
    }

    @Test
    void walkInNeedsAName() {
        assertThatThrownBy(() -> service.addWalkInStudent(event, "  ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.addWalkInStudent(event, "가".repeat(21))).isInstanceOf(ApiException.class);
    }

    @Test
    void claimCodeLinksTheWalkInAndBackdatesTheLinkSoEventRecordsShow() {
        Instant addedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        TutorStudent walkIn = TutorStudent.builder().id(UUID.randomUUID()).name("김하늘").ageBand("나이 미입력")
                .classGroup(event).tutor(tutor).parentClaimCode("ABCDEFGH23").createdAt(addedAt).build();
        when(students.findByClassGroup_IdAndParentClaimCodeAndLinkedParentUserIsNullAndDeletedAtIsNull(
                event.getId(), "ABCDEFGH23")).thenReturn(Optional.of(walkIn));
        when(students.lockById(walkIn.getId())).thenReturn(Optional.of(walkIn));

        // 보호자가 아이 이름을 다르게 적어도(별명 등) 코드의 학생에 잇는다.
        TutorStudent linked = service.enrollParentInClass(parent, event, "하늘이", 2020, null, null, "abcdefgh23");

        assertThat(linked).isSameAs(walkIn);
        assertThat(linked.getLinkedParentUser()).isEqualTo(parent);
        assertThat(linked.getStatus()).isEqualTo(TutorStudentStatus.CONFIRMED);
        assertThat(linked.getLinkedAt()).isEqualTo(addedAt);
        assertThat(linked.getParentClaimCode()).isNull();
        assertThat(linked.getBirthYear()).isEqualTo(2020);
        // 코드를 가진 보호자라 선생님에게 "확인해 주세요"를 보내지 않는다.
        verify(notifications, never()).publish(eq(tutor.getId()), anyString(), eq("김하늘 보호자 연결을 확인해 주세요"),
                anyString(), anyString(), anyString());
    }

    @Test
    void wrongOrUsedClaimCodeIsRejected() {
        when(students.findByClassGroup_IdAndParentClaimCodeAndLinkedParentUserIsNullAndDeletedAtIsNull(
                event.getId(), "ZZZZZZZZZZ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.enrollParentInClass(parent, event, "김하늘", 2020, null, null, "ZZZZZZZZZZ"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void withoutACodeTheLinkStartsNowAsBefore() {
        TutorStudent student = service.enrollParentInClass(parent, event, "이바다", 2020, null, null, null);

        assertThat(student.getLinkedAt()).isAfter(Instant.now().minus(1, ChronoUnit.MINUTES));
    }
}

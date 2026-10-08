package com.qstory.backend.org.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.ClassMembershipReason;
import com.qstory.backend.org.entity.TutorStudentClassHistory;
import com.qstory.backend.org.repository.TutorStudentClassHistoryRepository;
import com.qstory.backend.tutor.entity.TutorStudent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** 076 학생 반 이력: 지금 구간은 하나, 옮기면 닫고(먼저 내보낸 뒤) 새로 연다, 졸업은 닫기만. */
class StudentClassHistoryServiceTest {

    private final TutorStudentClassHistoryRepository repository = mock(TutorStudentClassHistoryRepository.class);
    private final StudentClassHistoryService service = new StudentClassHistoryService(repository);
    private final ClassGroup sun = ClassGroup.builder().id(UUID.randomUUID()).name("햇님반").build();
    private final ClassGroup star = ClassGroup.builder().id(UUID.randomUUID()).name("별님반").build();
    private final TutorStudent student = TutorStudent.builder().id(UUID.randomUUID()).name("민서")
            .createdAt(Instant.parse("2026-03-01T00:00:00Z")).build();

    @Test
    void joinedOpensOnceAndClosesAStaleOpenRowOfAnotherClass() {
        TutorStudentClassHistory stale = open(star);
        when(repository.findByTutorStudent_IdAndEndedAtIsNull(student.getId())).thenReturn(List.of(stale));
        Instant now = Instant.now();

        service.recordJoined(student, sun, now);

        assertEquals(now, stale.getEndedAt());
        assertEquals(ClassMembershipReason.MOVED, stale.getEndReason());
        ArgumentCaptor<TutorStudentClassHistory> saved = ArgumentCaptor.forClass(TutorStudentClassHistory.class);
        verify(repository).save(saved.capture());
        assertSame(sun, saved.getValue().getClassGroup());
        assertEquals(ClassMembershipReason.JOINED, saved.getValue().getReason());

        // 이미 이 반 구간이 열려 있으면(명단에 미리 있던 학생) 새로 열지 않는다.
        when(repository.findByTutorStudent_IdAndEndedAtIsNull(student.getId())).thenReturn(List.of(open(sun)));
        service.recordJoined(student, sun, now);
        verify(repository).save(any());
    }

    @Test
    void transferClosesAndFlushesBeforeOpening() {
        TutorStudentClassHistory current = open(sun);
        when(repository.findByTutorStudent_IdAndEndedAtIsNull(student.getId())).thenReturn(List.of(current));
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        UUID director = UUID.randomUUID();
        Instant now = Instant.now();

        TutorStudentClassHistory opened = service.transfer(
                student, star, ClassMembershipReason.MOVED, ClassMembershipReason.MOVED, now, director);

        assertEquals(now, current.getEndedAt());
        assertEquals(ClassMembershipReason.MOVED, current.getEndReason());
        assertEquals(director, current.getChangedBy());
        assertSame(star, opened.getClassGroup());
        assertEquals(ClassMembershipReason.MOVED, opened.getReason());
        assertEquals(director, opened.getChangedBy());
        // 부분 유니크 인덱스 - 닫은 행을 먼저 내보내야 새 행을 넣을 수 있다.
        InOrder order = inOrder(repository);
        order.verify(repository).flush();
        order.verify(repository).saveAndFlush(any());
    }

    @Test
    void closeEndsTheOpenRowOrBackfillsAClosedOne() {
        TutorStudentClassHistory current = open(sun);
        when(repository.findByTutorStudent_IdAndEndedAtIsNull(student.getId())).thenReturn(List.of(current));
        Instant now = Instant.now();

        assertSame(current, service.close(student, sun, ClassMembershipReason.GRADUATED, now, null));
        assertEquals(ClassMembershipReason.GRADUATED, current.getEndReason());
        verify(repository, never()).saveAndFlush(any());

        when(repository.findByTutorStudent_IdAndEndedAtIsNull(student.getId())).thenReturn(List.of());
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        TutorStudentClassHistory backfilled = service.close(student, sun, ClassMembershipReason.GRADUATED, now, null);
        assertEquals(student.getCreatedAt(), backfilled.getStartedAt());
        assertNotNull(backfilled.getEndedAt());
        assertEquals(ClassMembershipReason.GRADUATED, backfilled.getEndReason());
    }

    @Test
    void endedInClassKeepsTheLatestRowPerStudent() {
        TutorStudentClassHistory latest = open(sun);
        latest.setEndedAt(Instant.parse("2026-09-01T00:00:00Z"));
        TutorStudentClassHistory older = open(sun);
        older.setEndedAt(Instant.parse("2026-06-01T00:00:00Z"));
        when(repository.findEndedInClass(sun.getId())).thenReturn(List.of(latest, older));

        assertEquals(List.of(latest), service.endedInClass(sun.getId()));
    }

    private TutorStudentClassHistory open(ClassGroup classGroup) {
        return TutorStudentClassHistory.builder().id(1L).tutorStudent(student).classGroup(classGroup)
                .startedAt(Instant.parse("2026-03-01T00:00:00Z")).reason(ClassMembershipReason.JOINED).build();
    }
}

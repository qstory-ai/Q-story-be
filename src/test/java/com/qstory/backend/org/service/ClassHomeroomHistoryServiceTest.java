package com.qstory.backend.org.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.ClassHomeroomHistory;
import com.qstory.backend.org.repository.ClassHomeroomHistoryRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class ClassHomeroomHistoryServiceTest {

    private final ClassHomeroomHistoryRepository repository = mock(ClassHomeroomHistoryRepository.class);
    private final ClassHomeroomHistoryService service = new ClassHomeroomHistoryService(repository);
    private final ClassGroup classGroup = ClassGroup.builder().id(UUID.randomUUID()).name("햇님반").build();

    @Test
    void startClosesTheCurrentTermBeforeOpeningTheNextOne() {
        ClassHomeroomHistory current = ClassHomeroomHistory.builder().classGroup(classGroup)
                .tutor(AppUser.builder().id(UUID.randomUUID()).build()).startedAt(Instant.EPOCH).build();
        when(repository.findByClassGroup_IdAndEndedAtIsNull(classGroup.getId())).thenReturn(List.of(current));
        AppUser next = AppUser.builder().id(UUID.randomUUID()).build();
        Instant at = Instant.parse("2026-10-03T00:00:00Z");

        service.start(classGroup, next, at);

        assertEquals(at, current.getEndedAt());
        InOrder order = inOrder(repository);
        order.verify(repository).saveAll(List.of(current));
        order.verify(repository).flush();
        ArgumentCaptor<ClassHomeroomHistory> saved = ArgumentCaptor.forClass(ClassHomeroomHistory.class);
        order.verify(repository).save(saved.capture());
        assertSame(next, saved.getValue().getTutor());
        assertEquals(at, saved.getValue().getStartedAt());
        assertNull(saved.getValue().getEndedAt());
    }

    @Test
    void endWithoutCurrentTermDoesNothing() {
        when(repository.findByClassGroup_IdAndEndedAtIsNull(classGroup.getId())).thenReturn(List.of());

        service.end(classGroup, Instant.now());

        verify(repository, never()).saveAll(any());
        verify(repository, never()).flush();
    }
}

package com.qstory.backend.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.voiceresearch.service.VoiceResearchService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.transaction.annotation.Transactional;

class LegacyDeletedAccountCleanupServiceTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AccountErasureService erasure = mock(AccountErasureService.class);
    private final VoiceResearchService voiceResearch = mock(VoiceResearchService.class);
    private final LegacyDeletedAccountCleanupService service =
            new LegacyDeletedAccountCleanupService(users, erasure, voiceResearch);

    private static AppUser deleted(Role role) {
        return AppUser.builder().id(UUID.randomUUID()).role(role).loginId("deleted:x:a@b.c").email("a@b.c")
                .displayName("x").createdAt(Instant.now()).deletedAt(Instant.parse("2026-05-01T00:00:00Z")).build();
    }

    @Test
    void dryRunOnlyCounts() {
        when(users.countLegacyDeletedAccounts()).thenReturn(4L);

        assertEquals(4L, service.countPending());

        verify(users, never()).findLegacyDeletedAccounts();
        verifyNoInteractions(erasure, voiceResearch);
    }

    @Test
    void eachAccountIsErasedSeparatelyAndAFailureDoesNotStopTheRest() {
        AppUser parent = deleted(Role.PARENT);
        AppUser broken = deleted(Role.TUTOR);
        AppUser director = deleted(Role.DIRECTOR);
        when(users.findLegacyDeletedAccounts()).thenReturn(List.of(parent, broken, director));
        doThrow(new IllegalStateException("db")).when(erasure).eraseLegacyDeleted(broken.getId());

        LegacyDeletedAccountCleanupService.Result result = service.run();

        assertEquals(new LegacyDeletedAccountCleanupService.Result(3, 2, 1), result);
        verify(erasure).eraseLegacyDeleted(parent.getId());
        verify(erasure).eraseLegacyDeleted(broken.getId());
        verify(erasure).eraseLegacyDeleted(director.getId());
    }

    @Test
    void parentRecordingsAreWithdrawnBeforeTheErasureTransaction() {
        AppUser parent = deleted(Role.PARENT);
        AppUser tutor = deleted(Role.TUTOR);
        when(users.findLegacyDeletedAccounts()).thenReturn(List.of(parent, tutor));

        service.run();

        InOrder order = Mockito.inOrder(voiceResearch, erasure);
        order.verify(voiceResearch).withdrawForDeletedAccount(parent.getId());
        order.verify(erasure).eraseLegacyDeleted(parent.getId());
        verify(voiceResearch, never()).withdrawForDeletedAccount(tutor.getId());
    }

    @Test
    void voiceResearchFailureStillErasesTheAccount() {
        AppUser parent = deleted(Role.PARENT);
        when(users.findLegacyDeletedAccounts()).thenReturn(List.of(parent));
        doThrow(new RuntimeException("storage")).when(voiceResearch).withdrawForDeletedAccount(any());

        LegacyDeletedAccountCleanupService.Result result = service.run();

        assertEquals(new LegacyDeletedAccountCleanupService.Result(1, 1, 0), result);
        verify(erasure).eraseLegacyDeleted(parent.getId());
    }

    @Test
    void nothingToCleanUp() {
        when(users.findLegacyDeletedAccounts()).thenReturn(List.of());

        assertEquals(new LegacyDeletedAccountCleanupService.Result(0, 0, 0), service.run());
    }

    /** 하나의 트랜잭션으로 묶이면 한 계정의 실패가 앞서 정리한 계정까지 되돌린다. */
    @Test
    void runIsNotOneBigTransaction() throws Exception {
        assertNull(LegacyDeletedAccountCleanupService.class.getMethod("run").getAnnotation(Transactional.class));
        assertNull(LegacyDeletedAccountCleanupService.class.getAnnotation(Transactional.class));
    }
}

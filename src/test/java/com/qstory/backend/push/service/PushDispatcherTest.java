package com.qstory.backend.push.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.qstory.backend.push.repository.PushDeviceTokenRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PushDispatcherTest {

    private final FcmClient fcm = mock(FcmClient.class);
    private final PushDeviceTokenRepository tokens = mock(PushDeviceTokenRepository.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final PushDispatcher dispatcher = new PushDispatcher(fcm, tokens, queued::add);
    private final UUID userId = UUID.randomUUID();
    private final PushMessage message = new PushMessage(UUID.randomUUID(), "tutor-report", "제목", "본문", "/x");

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void unconfiguredFcmIsANoOp() {
        when(fcm.enabled()).thenReturn(false);

        dispatcher.dispatchAfterCommit(userId, message);

        verifyNoInteractions(tokens);
        org.junit.jupiter.api.Assertions.assertTrue(queued.isEmpty());
    }

    @Test
    void sendsOnlyAfterCommitAndOffTheRequestThread() {
        when(fcm.enabled()).thenReturn(true);
        when(tokens.findActiveTokens(userId)).thenReturn(List.of("t1"));
        when(fcm.send("t1", message)).thenReturn(FcmSendResult.sent(200));
        TransactionSynchronizationManager.initSynchronization();

        dispatcher.dispatchAfterCommit(userId, message);

        // 커밋 전에는 아무것도 하지 않는다 - 롤백되면 푸시도 없다.
        org.junit.jupiter.api.Assertions.assertTrue(queued.isEmpty());
        verifyNoInteractions(tokens);

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(TransactionSynchronization::afterCommit);
        // 커밋 뒤에는 실행기로 넘길 뿐, 요청 스레드에서 FCM을 부르지 않는다.
        org.junit.jupiter.api.Assertions.assertEquals(1, queued.size());
        verify(fcm, never()).send(anyString(), any());

        queued.get(0).run();
        verify(fcm).send("t1", message);
    }

    @Test
    void rolledBackTransactionSendsNothing() {
        when(fcm.enabled()).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();

        dispatcher.dispatchAfterCommit(userId, message);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        org.junit.jupiter.api.Assertions.assertTrue(queued.isEmpty());
    }

    @Test
    void deliverSendsToEveryActiveTokenAndDisablesDeadOnes() {
        when(tokens.findActiveTokens(userId)).thenReturn(List.of("alive", "dead", "flaky"));
        when(fcm.send("alive", message)).thenReturn(FcmSendResult.sent(200));
        when(fcm.send("dead", message))
                .thenReturn(new FcmSendResult(FcmSendResult.Outcome.TOKEN_INVALID, 404, "UNREGISTERED"));
        when(fcm.send("flaky", message))
                .thenReturn(new FcmSendResult(FcmSendResult.Outcome.FAILED, 503, "UNAVAILABLE"));
        when(tokens.disable(eq("dead"), any(Instant.class))).thenReturn(1);

        dispatcher.deliver(userId, message);

        verify(tokens).disable(eq("dead"), any(Instant.class));
        verify(tokens, never()).disable(eq("alive"), any());
        verify(tokens, never()).disable(eq("flaky"), any());
    }

    @Test
    void userWithoutTokensSendsNothing() {
        when(tokens.findActiveTokens(userId)).thenReturn(List.of());

        dispatcher.deliver(userId, message);

        verify(fcm, never()).send(anyString(), any());
    }

    @Test
    void repositoryFailureDuringDeliveryIsSwallowed() {
        when(tokens.findActiveTokens(userId)).thenThrow(new IllegalStateException("db down"));

        dispatcher.deliver(userId, message);

        verify(fcm, never()).send(anyString(), any());
    }
}

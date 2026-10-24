package com.qstory.backend.betaevents.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qstory.backend.betaevents.entity.StoryEvent;
import com.qstory.backend.betaevents.entity.StorySession;
import com.qstory.backend.betaevents.repository.BetaEventRepository;
import com.qstory.backend.betaevents.util.BetaEventValidator;
import com.qstory.backend.common.enums.EventName;
import com.qstory.backend.common.enums.EventSource;
import com.qstory.backend.shadow.service.ShadowIntentCollectionService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/** 묶음 저장 - 한 트랜잭션으로 넣고, 한 건이 실패하면 한 건씩 다시 넣어 그 건만 버린다. received_at은 요청 시각. */
class BetaEventServiceBatchTest {

    private final BetaEventRepository repository = mock(BetaEventRepository.class);
    private final AtomicInteger transactionsOpened = new AtomicInteger();
    private final TransactionOperations transactions = new TransactionOperations() {
        @Override
        public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
            transactionsOpened.incrementAndGet();
            return action.doInTransaction(null);
        }
    };
    private final BetaEventService service =
            new BetaEventService(repository, mock(ShadowIntentCollectionService.class), transactions);
    private final List<StoryEvent> saved = new ArrayList<>();

    private BetaEventIngestQueue.Pending pending(Instant receivedAt) {
        UUID sessionId = UUID.randomUUID();
        StorySession session = new StorySession();
        session.setId(sessionId);
        when(repository.findSession(sessionId)).thenReturn(session);
        return new BetaEventIngestQueue.Pending(
                new BetaEventValidator.ParsedEvent(
                        UUID.randomUUID(), sessionId, EventName.APP_ENTRY, EventSource.APP, receivedAt, Map.of()),
                null, receivedAt);
    }

    @Test
    void storesTheWholeBatchInOneTransaction() {
        when(repository.saveEvent(any())).thenAnswer(invocation -> {
            saved.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        Instant receivedAt = Instant.parse("2026-10-24T12:29:00Z");

        service.storeBatch(List.of(pending(receivedAt), pending(receivedAt), pending(receivedAt)));

        assertThat(transactionsOpened.get()).isEqualTo(1);
        assertThat(saved).hasSize(3).allSatisfy(event -> assertThat(event.getReceivedAt()).isEqualTo(receivedAt));
    }

    @Test
    void oneBadEventOnlyDropsItself() {
        Instant receivedAt = Instant.now();
        BetaEventIngestQueue.Pending bad = pending(receivedAt);
        when(repository.saveEvent(any())).thenAnswer(invocation -> {
            StoryEvent event = invocation.getArgument(0);
            if (event.getId().equals(bad.event().eventId())) {
                throw new IllegalStateException("constraint");
            }
            saved.add(event);
            return event;
        });

        service.storeBatch(List.of(pending(receivedAt), bad, pending(receivedAt)));

        // 묶음 1번이 실패하고, 한 건씩 3번 다시 넣는다(목이라 첫 시도의 저장도 saved에 남는다).
        assertThat(transactionsOpened.get()).isEqualTo(4);
        assertThat(saved).extracting(StoryEvent::getId).doesNotContain(bad.event().eventId());
        assertThat(saved.stream().map(StoryEvent::getId).distinct().count()).isEqualTo(2);
    }
}

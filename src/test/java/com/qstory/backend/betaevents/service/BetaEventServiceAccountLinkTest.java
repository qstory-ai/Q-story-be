package com.qstory.backend.betaevents.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qstory.backend.betaevents.entity.StorySession;
import com.qstory.backend.betaevents.repository.BetaEventRepository;
import com.qstory.backend.betaevents.util.BetaEventValidator;
import com.qstory.backend.common.enums.EventName;
import com.qstory.backend.common.enums.EventSource;
import com.qstory.backend.shadow.service.ShadowIntentCollectionService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 보안 점검 L2 - 통계 세션에는 처음 이어진 계정만 남는다. 남의 베타 세션 id로 이벤트를 보내 계정 연결을 바꾸지 못한다. */
class BetaEventServiceAccountLinkTest {

    private final BetaEventRepository repository = mock(BetaEventRepository.class);
    private final BetaEventService service = new BetaEventService(repository, mock(ShadowIntentCollectionService.class));
    private final UUID sessionId = UUID.randomUUID();

    private StorySession sessionOwnedBy(UUID userId) {
        StorySession session = new StorySession();
        session.setId(sessionId);
        session.setUserId(userId);
        when(repository.findSession(sessionId)).thenReturn(session);
        when(repository.saveEvent(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return session;
    }

    private BetaEventValidator.ParsedEvent event() {
        return new BetaEventValidator.ParsedEvent(
                UUID.randomUUID(), sessionId, EventName.APP_ENTRY, EventSource.APP, Instant.now(), Map.of());
    }

    @Test
    void anotherAccountCannotTakeOverALinkedSession() {
        UUID owner = UUID.randomUUID();
        StorySession session = sessionOwnedBy(owner);

        service.record(event(), UUID.randomUUID());

        assertThat(session.getUserId()).isEqualTo(owner);
    }

    @Test
    void anonymousSessionIsLinkedToTheFirstAccount() {
        StorySession session = sessionOwnedBy(null);
        UUID first = UUID.randomUUID();

        service.record(event(), first);

        assertThat(session.getUserId()).isEqualTo(first);
    }
}

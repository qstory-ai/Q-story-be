package com.qstory.backend.botdispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.qstory.backend.config.BotDispatchProperties;
import com.qstory.backend.config.BotDispatchProperties.Job;
import com.qstory.backend.discord.repository.DiscordStateRepository;
import com.qstory.backend.discord.service.DiscordChannel;
import com.qstory.backend.discord.service.DiscordMention;
import com.qstory.backend.discord.service.DiscordNotifier;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.SimpleTriggerContext;

class BotDispatchSchedulerTest {

    // 2026-10-15 14:00 UTC = 10/15 23:00 KST(목)
    private static final Instant NOW = Instant.parse("2026-10-15T14:00:00Z");
    private static final LocalDate KST_DAY = LocalDate.of(2026, 10, 15);
    private static final Job SYNC = new Job("linear-notion-sync.yml", "0 5 * * * *");

    private final GitHubDispatchClient client = mock(GitHubDispatchClient.class);
    private final DiscordNotifier notifier = mock(DiscordNotifier.class);
    private final DiscordStateRepository state = mock(DiscordStateRepository.class);

    private BotDispatchScheduler scheduler(Boolean enabled, String token, Job... jobs) {
        return new BotDispatchScheduler(new BotDispatchProperties(enabled, token, "org/bot", null, List.of(jobs)),
                client, notifier, state, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @BeforeEach
    void setUp() {
        when(notifier.configured(DiscordChannel.ALERTS)).thenReturn(true);
        when(notifier.send(any(), anyString(), anyList())).thenReturn(true);
    }

    private static Instant next(String cron, Instant from) {
        return BotDispatchScheduler.trigger(new Job("x.yml", cron))
                .nextExecution(new SimpleTriggerContext(Clock.fixed(from, ZoneOffset.UTC)));
    }

    @Test
    void cronIsKoreanTimeAndSkipsMissedRunsAfterRestart() {
        // 23:00 KST에 막 부팅 - 놓친 22:00 리마인더를 지금 보내지 않고 다음 평일(금) 22:00으로.
        assertThat(next("0 0 22 * * MON-FRI", NOW)).isEqualTo(Instant.parse("2026-10-16T13:00:00Z"));
        // 매시 5분: 23:00 → 23:05 KST
        assertThat(next("0 5 * * * *", NOW)).isEqualTo(Instant.parse("2026-10-15T14:05:00Z"));
        // 평일 09:00: 금 09:00 KST = 목 24:00 UTC
        assertThat(next("0 0 9 * * MON-FRI", NOW)).isEqualTo(Instant.parse("2026-10-16T00:00:00Z"));
        // 금 22:00 KST 이후면 주말을 건너뛰고 월 21:30 KST
        assertThat(next("0 30 21 * * MON-FRI", Instant.parse("2026-10-16T13:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-19T12:30:00Z"));
    }

    @Test
    void noTokenOrKillSwitchRegistersNothing() {
        assertThat(scheduler(null, null, SYNC).tasks()).isEmpty();
        assertThat(scheduler(null, "  ", SYNC).tasks()).isEmpty();
        assertThat(scheduler(false, "token", SYNC).tasks()).isEmpty();
        assertThat(scheduler(null, "token", SYNC, new Job("off.yml", "-")).tasks()).hasSize(1);
    }

    @Test
    void successSendsNoAlert() {
        when(client.dispatch("token", "org/bot", "linear-notion-sync.yml", "main"))
                .thenReturn(new GitHubDispatchClient.Result(204, null));

        scheduler(null, "token", SYNC).run(SYNC);

        verifyNoInteractions(state);
        verify(notifier, never()).send(any(), anyString(), anyList());
    }

    @Test
    void failureAlertsDevOncePerWorkflowPerDay() {
        when(client.dispatch(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GitHubDispatchClient.Result(403, null));
        when(state.markOnce("bot-dispatch-linear-notion-sync.yml", KST_DAY)).thenReturn(true, false);
        BotDispatchScheduler scheduler = scheduler(null, "token", SYNC);

        scheduler.run(SYNC);
        scheduler.run(SYNC);

        verify(state, times(2)).markOnce("bot-dispatch-linear-notion-sync.yml", KST_DAY);
        verify(notifier, times(1)).send(eq(DiscordChannel.ALERTS), contains("status=403"), eq(List.of(DiscordMention.DEV)));
    }

    @Test
    void exceptionIsAFailureToo() {
        when(client.dispatch(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GitHubDispatchClient.Result(-1, "HttpTimeoutException"));
        when(state.markOnce(anyString(), any())).thenReturn(true);

        scheduler(null, "token", SYNC).run(SYNC);

        verify(notifier).send(eq(DiscordChannel.ALERTS), contains("HttpTimeoutException"), anyList());
    }

    @Test
    void unmarksWhenAlertCouldNotBeQueued() {
        when(client.dispatch(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GitHubDispatchClient.Result(500, null));
        when(state.markOnce(anyString(), any())).thenReturn(true);
        when(notifier.send(any(), anyString(), anyList())).thenReturn(false);

        scheduler(null, "token", SYNC).run(SYNC);

        verify(state).unmark("bot-dispatch-linear-notion-sync.yml", KST_DAY);
    }

    @Test
    void newsChannelOffSkipsAlertBookkeeping() {
        when(client.dispatch(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GitHubDispatchClient.Result(500, null));
        when(notifier.configured(DiscordChannel.ALERTS)).thenReturn(false);

        scheduler(null, "token", SYNC).run(SYNC);

        verifyNoInteractions(state);
    }

    @Test
    void requestShape() {
        assertThat(GitHubDispatchClient.uri("org/bot", "daily-scrum.yml").toString())
                .isEqualTo("https://api.github.com/repos/org/bot/actions/workflows/daily-scrum.yml/dispatches");
        assertThat(GitHubDispatchClient.body("main")).isEqualTo("{\"ref\":\"main\"}");
    }
}

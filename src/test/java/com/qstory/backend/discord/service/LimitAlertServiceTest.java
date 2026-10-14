package com.qstory.backend.discord.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.config.DiscordProperties;
import com.qstory.backend.discord.repository.DiscordStateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LimitAlertServiceTest {

    // 2026-10-14 05:00 UTC = 10/13 22:00 미국 태평양(PDT) = 10/14 14:00 KST
    private static final Instant NOW = Instant.parse("2026-10-14T05:00:00Z");
    private static final LocalDate PT_DAY = LocalDate.of(2026, 10, 13);

    private final DiscordProperties properties = DiscordPayloadTest.props("111", "222");
    private final DiscordNotifier notifier = mock(DiscordNotifier.class);
    private final DiscordStateRepository state = mock(DiscordStateRepository.class);
    private final DiscordJobRunner jobs = mock(DiscordJobRunner.class);
    private final OpenRouterBalanceClient openRouter = mock(OpenRouterBalanceClient.class);
    private final LimitAlertService service = new LimitAlertService(
            properties, notifier, state, jobs, openRouter, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(notifier.send(any(), anyString(), anyList())).thenReturn(true);
    }

    @Test
    void belowFirstStepSendsNothing() {
        when(state.incrementUsage(LimitAlertService.GEMINI_PROVIDER, PT_DAY)).thenReturn(79);

        service.countGeminiCall();

        verify(state, never()).markOnce(anyString(), any());
        verify(notifier, never()).send(any(), anyString(), anyList());
    }

    @Test
    void crossingEightyWarnsDevOnceForThatPacificDay() {
        when(state.incrementUsage(LimitAlertService.GEMINI_PROVIDER, PT_DAY)).thenReturn(80, 81);
        when(state.markOnce("gemini-tts-80", PT_DAY)).thenReturn(true, false);

        service.countGeminiCall();
        service.countGeminiCall();

        verify(notifier).send(eq(DiscordChannel.NEWS), contains("80/100회"), eq(List.of(DiscordMention.DEV)));
    }

    @Test
    void crossingNinetyFiveSendsTheHigherStepOnly() {
        when(state.incrementUsage(LimitAlertService.GEMINI_PROVIDER, PT_DAY)).thenReturn(95);
        when(state.markOnce("gemini-tts-95", PT_DAY)).thenReturn(true);
        when(state.markOnce("gemini-tts-80", PT_DAY)).thenReturn(true);

        service.countGeminiCall();

        verify(notifier).send(eq(DiscordChannel.NEWS), contains("95회 넘음"), eq(List.of(DiscordMention.DEV)));
        verify(notifier, never()).send(any(), contains("80회 넘음"), anyList());
    }

    @Test
    void geminiCallsAreCountedOffTheRequestThread() {
        service.onCall();
        verify(jobs).submit(eq("gemini-tts-count"), any());
        verify(state, never()).incrementUsage(anyString(), any());
    }

    @Test
    void lowOpenRouterBalanceMentionsDevAndOpsOncePerDay() {
        when(openRouter.remainingUsd()).thenReturn(OptionalDouble.of(3.2));
        when(state.markOnce("openrouter-low-balance", LocalDate.of(2026, 10, 14))).thenReturn(true, false);

        service.checkOpenRouterBalance();
        service.checkOpenRouterBalance();

        verify(notifier).send(eq(DiscordChannel.NEWS), contains("$3.20"),
                eq(List.of(DiscordMention.DEV, DiscordMention.OPS)));
    }

    @Test
    void healthyOrUnknownBalanceIsQuiet() {
        when(openRouter.remainingUsd()).thenReturn(OptionalDouble.of(11.4), OptionalDouble.empty());

        service.checkOpenRouterBalance();
        service.checkOpenRouterBalance();

        verify(state, never()).markOnce(anyString(), any());
    }

    @Test
    void failedHandOffUnmarks() {
        when(openRouter.remainingUsd()).thenReturn(OptionalDouble.of(1.0));
        when(state.markOnce(anyString(), any())).thenReturn(true);
        when(notifier.send(any(), anyString(), anyList())).thenReturn(false);

        service.checkOpenRouterBalance();

        verify(state).unmark("openrouter-low-balance", LocalDate.of(2026, 10, 14));
    }
}

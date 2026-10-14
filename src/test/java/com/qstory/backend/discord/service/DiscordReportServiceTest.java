package com.qstory.backend.discord.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.config.DiscordProperties;
import com.qstory.backend.discord.repository.DiscordMetricsRepository;
import com.qstory.backend.discord.repository.DiscordMetricsRepository.Funnel;
import com.qstory.backend.discord.repository.DiscordMetricsRepository.JoinRequests;
import com.qstory.backend.discord.repository.DiscordMetricsRepository.Surveys;
import com.qstory.backend.discord.repository.DiscordStateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DiscordReportServiceTest {

    // 2026-10-14 14:41 KST
    private static final Instant NOW = Instant.parse("2026-10-14T05:41:10Z");

    private final DiscordProperties properties = DiscordPayloadTest.props("111", "222");
    private final DiscordNotifier notifier = mock(DiscordNotifier.class);
    private final DiscordMetricsRepository metrics = mock(DiscordMetricsRepository.class);
    private final DiscordStateRepository state = mock(DiscordStateRepository.class);
    private final DiscordReportService service =
            new DiscordReportService(properties, notifier, metrics, state, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(metrics.funnel(any(), any())).thenReturn(new Funnel(0, 0, 0));
        when(metrics.signupsByRole(any(), any())).thenReturn(DiscordMessagesTest.signups(0, 0, 0));
        when(metrics.surveys(any(), any())).thenReturn(new Surveys(0, null, null));
        when(metrics.joinRequests(any(), any())).thenReturn(new JoinRequests(0, 4));
        when(notifier.send(any(), anyString(), anyList())).thenReturn(true);
    }

    @Test
    void dailySummaryGoesOutOncePerDayWithOpsMention() {
        LocalDate yesterday = LocalDate.of(2026, 10, 13);
        when(state.markOnce(DiscordReportService.DAILY_KEY, yesterday)).thenReturn(true, false);

        service.scheduledDailyMetrics();
        service.scheduledDailyMetrics();

        verify(notifier).send(eq(DiscordChannel.METRICS), anyString(), eq(List.of(DiscordMention.OPS)));
    }

    @Test
    void dailySummaryCountsWholeKstDays() {
        when(state.markOnce(any(), any())).thenReturn(true);
        service.scheduledDailyMetrics();

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(metrics, org.mockito.Mockito.times(2)).funnel(from.capture(), to.capture());
        assertEquals(Instant.parse("2026-10-12T15:00:00Z"), from.getAllValues().get(0)); // 10/13 00:00 KST
        assertEquals(Instant.parse("2026-10-13T15:00:00Z"), to.getAllValues().get(0));
        verify(metrics).gaTraffic(LocalDate.of(2026, 10, 13));
        verify(metrics).gaTraffic(LocalDate.of(2026, 10, 12));
    }

    @Test
    void failedHandOffUnmarksSoTheNextRunRetries() {
        LocalDate yesterday = LocalDate.of(2026, 10, 13);
        when(state.markOnce(DiscordReportService.DAILY_KEY, yesterday)).thenReturn(true);
        when(notifier.send(any(), anyString(), anyList())).thenReturn(false);

        service.scheduledDailyMetrics();

        verify(state).unmark(DiscordReportService.DAILY_KEY, yesterday);
    }

    @Test
    void emptyWindowSendsNothingButAdvancesCursor() {
        when(state.state(DiscordReportService.DIGEST_CURSOR)).thenReturn(Optional.empty());

        assertFalse(service.runNewsDigest());

        verify(notifier, never()).send(any(), anyString(), anyList());
        verify(state).putState(DiscordReportService.DIGEST_CURSOR, "2026-10-14T05:30:00Z");
    }

    @Test
    void windowWithReactionsIsBatchedIntoOneOpsMessage() {
        Instant cursor = Instant.parse("2026-10-14T05:00:00Z");
        when(state.state(DiscordReportService.DIGEST_CURSOR)).thenReturn(Optional.of(cursor.toString()));
        Instant end = Instant.parse("2026-10-14T05:30:00Z");
        when(metrics.surveys(cursor, end)).thenReturn(new Surveys(2, 4.5, 5.0));
        when(metrics.launchNotifications(cursor, end)).thenReturn(3L);

        assertTrue(service.runNewsDigest());

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notifier).send(eq(DiscordChannel.NEWS), text.capture(), eq(List.of(DiscordMention.OPS)));
        assertTrue(text.getValue().startsWith("**[사용자 반응]** 14:00~14:30"), text.getValue());
        assertTrue(text.getValue().contains("출시 알림 신청 3건"), text.getValue());
    }

    @Test
    void windowAlreadyCountedIsSkipped() {
        when(state.state(DiscordReportService.DIGEST_CURSOR)).thenReturn(Optional.of("2026-10-14T05:30:00Z"));

        assertFalse(service.runNewsDigest());

        verify(metrics, never()).surveys(any(), any());
        verify(state, never()).putState(anyString(), anyString());
    }

    @Test
    void longGapCatchesUpAtMostSixHours() {
        when(state.state(DiscordReportService.DIGEST_CURSOR)).thenReturn(Optional.of("2026-10-10T00:00:00Z"));

        service.runNewsDigest();

        verify(metrics).surveys(Instant.parse("2026-10-13T23:30:00Z"), Instant.parse("2026-10-14T05:30:00Z"));
    }

    @Test
    void killSwitchStopsEverything() {
        DiscordProperties off = new DiscordProperties(false, "m", "d", "n", "1", "2", true, true, true, null, null,
                100, "80,95", 5.0);
        DiscordReportService disabled =
                new DiscordReportService(off, notifier, metrics, state, Clock.fixed(NOW, ZoneOffset.UTC));

        disabled.scheduledDailyMetrics();
        assertFalse(disabled.runNewsDigest());

        verify(state, never()).markOnce(any(), any());
        verify(notifier, never()).send(any(), anyString(), anyList());
    }
}

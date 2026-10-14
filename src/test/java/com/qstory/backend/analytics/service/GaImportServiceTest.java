package com.qstory.backend.analytics.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.qstory.backend.analytics.repository.GaMetricsRepository;
import com.qstory.backend.config.GaProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionOperations;

class GaImportServiceTest {

    // 2026-10-14 16:30 UTC = 2026-10-15 01:30 KST - GA 날짜(한국 시간)로는 15일이 오늘.
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T16:30:00Z"), ZoneOffset.UTC);
    private final GaDataClient client = mock(GaDataClient.class);
    private final GaMetricsRepository repository = mock(GaMetricsRepository.class);
    private final GaImportService service = new GaImportService(
            client, repository, TransactionOperations.withoutTransaction(),
            new GaProperties("{}", "555537619", true, 3, 30), clock);

    private static final GaRows.Key KEY =
            new GaRows.Key(LocalDate.of(2026, 10, 15), "demo.qstory.ai.kr", "qr", "offline", "qr-main", "web", "app");

    @Test
    void recentImportReplacesLastThreeKstDaysAndRecordsRun() throws Exception {
        when(client.enabled()).thenReturn(true);
        when(repository.isEmpty()).thenReturn(false);
        LocalDate start = LocalDate.of(2026, 10, 13);
        LocalDate end = LocalDate.of(2026, 10, 15);
        List<GaRows.EventRow> events = List.of(new GaRows.EventRow(KEY, "story_start", 3, 2, 2));
        List<GaRows.TrafficRow> traffic = List.of(new GaRows.TrafficRow(KEY, 5, 4, 6, 5, 20));
        when(client.events(start, end)).thenReturn(events);
        when(client.traffic(start, end)).thenReturn(traffic);

        assertTrue(service.importRecent());

        InOrder order = inOrder(repository);
        order.verify(repository).replaceEvents(eq(start), eq(end), eq(events), any());
        order.verify(repository).replaceTraffic(eq(start), eq(end), eq(traffic), any());
        order.verify(repository).recordRun(any(), any(), eq(start), eq(end), eq(true), eq(1), eq(1), isNull());
    }

    @Test
    void emptyTableBackfillsInitialDays() throws Exception {
        when(client.enabled()).thenReturn(true);
        when(repository.isEmpty()).thenReturn(true);
        when(client.events(any(), any())).thenReturn(List.of());
        when(client.traffic(any(), any())).thenReturn(List.of());

        assertTrue(service.importRecent());

        verify(client).events(LocalDate.of(2026, 9, 16), LocalDate.of(2026, 10, 15));
    }

    @Test
    void failedFetchKeepsExistingRowsAndRecordsFailure() throws Exception {
        when(client.enabled()).thenReturn(true);
        when(client.events(any(), any())).thenReturn(List.of());
        when(client.traffic(any(), any())).thenThrow(new GaDataClient.GaApiException("http-429 RESOURCE_EXHAUSTED"));

        assertFalse(service.importRange(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 15)));

        verify(repository, never()).replaceEvents(any(), any(), anyList(), any());
        verify(repository, never()).replaceTraffic(any(), any(), anyList(), any());
        verify(repository).recordRun(any(), any(), any(), any(), eq(false), anyInt(), anyInt(),
                eq("http-429 RESOURCE_EXHAUSTED"));
    }

    @Test
    void disabledClientDoesNothing() {
        when(client.enabled()).thenReturn(false);

        assertFalse(service.importRecent());

        verifyNoInteractions(repository);
    }
}

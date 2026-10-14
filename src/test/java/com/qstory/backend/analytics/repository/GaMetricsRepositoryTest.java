package com.qstory.backend.analytics.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.qstory.backend.analytics.service.GaRows;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** 갈아 끼우기 순서와 바인딩 값. SQL 자체는 운영 DB 사본에서 트랜잭션 롤백으로 확인했다(PR 설명). */
class GaMetricsRepositoryTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GaMetricsRepository repository = new GaMetricsRepository(jdbc);
    private static final LocalDate START = LocalDate.of(2026, 10, 12);
    private static final LocalDate END = LocalDate.of(2026, 10, 14);
    private static final GaRows.Key KEY =
            new GaRows.Key(LocalDate.of(2026, 10, 14), "demo.qstory.ai.kr", "qr", "offline", "qr-main", "ios", "app");

    @Test
    void eventsDeleteTheRangeThenUpsertEachRow() {
        repository.replaceEvents(START, END, List.of(
                new GaRows.EventRow(KEY, "sign_up", 2, 2, 2),
                new GaRows.EventRow(KEY, "story_complete", 5, 3, 4)), Instant.parse("2026-10-14T20:00:00Z"));

        ArgumentCaptor<SqlParameterSource> range = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(eq(GaMetricsRepository.DELETE_EVENTS), range.capture());
        assertEquals(Date.valueOf(START), range.getValue().getValue("start"));
        assertEquals(Date.valueOf(END), range.getValue().getValue("end"));

        ArgumentCaptor<SqlParameterSource[]> batch = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(jdbc).batchUpdate(eq(GaMetricsRepository.UPSERT_EVENT), batch.capture());
        SqlParameterSource second = batch.getValue()[1];
        assertEquals(2, batch.getValue().length);
        assertEquals(Date.valueOf(KEY.date()), second.getValue("date"));
        assertEquals("qr", second.getValue("source"));
        assertEquals("ios", second.getValue("platform"));
        assertEquals("story_complete", second.getValue("eventName"));
        assertEquals(5L, second.getValue("eventCount"));
        assertEquals(3L, second.getValue("totalUsers"));
        assertTrue(GaMetricsRepository.UPSERT_EVENT.contains("on conflict"));
    }

    @Test
    void trafficWithNoRowsStillClearsTheRange() {
        repository.replaceTraffic(START, END, List.of(), Instant.now());

        verify(jdbc).update(eq(GaMetricsRepository.DELETE_TRAFFIC), any(SqlParameterSource.class));
        verify(jdbc, never()).batchUpdate(anyString(), any(SqlParameterSource[].class));
    }

    @Test
    void trafficRowBindsAllMetrics() {
        repository.replaceTraffic(START, END, List.of(new GaRows.TrafficRow(KEY, 6, 4, 10, 9, 50)), Instant.now());

        ArgumentCaptor<SqlParameterSource[]> batch = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(jdbc).batchUpdate(eq(GaMetricsRepository.UPSERT_TRAFFIC), batch.capture());
        SqlParameterSource row = batch.getValue()[0];
        assertEquals(6L, row.getValue("totalUsers"));
        assertEquals(4L, row.getValue("newUsers"));
        assertEquals(10L, row.getValue("sessions"));
        assertEquals(9L, row.getValue("engagedSessions"));
        assertEquals(50L, row.getValue("pageViews"));
    }

    @Test
    void runLogTrimsLongError() {
        repository.recordRun(Instant.now(), Instant.now(), START, END, false, 0, 0, "x".repeat(900));

        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(eq(GaMetricsRepository.INSERT_RUN), params.capture());
        assertEquals("FAILED", params.getValue().getValue("status"));
        assertEquals(500, ((String) params.getValue().getValue("error")).length());
    }
}

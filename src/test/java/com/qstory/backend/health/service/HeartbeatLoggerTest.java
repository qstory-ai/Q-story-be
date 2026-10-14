package com.qstory.backend.health.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class HeartbeatLoggerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void recordsTheFirstAndFastestOfThreeSelectOnes() {
        // 시작·끝 시각 쌍(나노초): 첫 번째 250ms(연결 꺼내기 포함), 그다음 240ms, 238ms.
        Iterator<Long> clock = List.of(0L, 250_000_000L, 1_000_000_000L, 1_240_000_000L, 2_000_000_000L, 2_238_000_000L)
                .iterator();
        HeartbeatLogger logger = new HeartbeatLogger(jdbc, clock::next);

        HeartbeatLogger.DbPing ping = logger.pingDb();

        assertEquals(250, ping.firstMs());
        assertEquals(238, ping.minMs());
        assertNull(ping.error());
        verify(jdbc, times(HeartbeatLogger.DB_PINGS)).queryForObject(eq("select 1"), eq(Integer.class));
    }

    @Test
    void unreachableDbIsLoggedAsMinusOneWithoutFailingTheHeartbeat() {
        when(jdbc.queryForObject("select 1", Integer.class)).thenThrow(new DataAccessResourceFailureException("down"));
        HeartbeatLogger logger = new HeartbeatLogger(jdbc, System::nanoTime);

        HeartbeatLogger.DbPing ping = logger.pingDb();

        assertEquals(-1, ping.firstMs());
        assertEquals("DataAccessResourceFailureException", ping.error());
        logger.beat();
    }
}

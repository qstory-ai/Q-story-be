package com.qstory.backend.finops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GcpBillingClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsQueryRows() throws Exception {
        var rows = GcpBillingClient.parse(mapper.readTree("""
                {"jobComplete":true,"rows":[
                  {"f":[{"v":"2026-10-25"},{"v":"Gemini API"},{"v":"TTS output"},{"v":"KRW"},{"v":"1400.5"},{"v":"1.0"}]},
                  {"f":[{"v":"2026-10-25"},{"v":"BigQuery"},{"v":"Analysis"},{"v":"USD"},{"v":null},{"v":null}]}
                ]}
                """));

        assertThat(rows).containsExactly(
                new GcpBillingClient.Row(LocalDate.of(2026, 10, 25), "Gemini API", "TTS output", "KRW", 1400.5, 1.0));
    }

    @Test
    void emptyResultHasNoRows() throws Exception {
        assertThat(GcpBillingClient.parse(mapper.readTree("{\"jobComplete\":true}"))).isEmpty();
    }

    @Test
    void disabledWithoutServiceAccount() {
        assertThat(new GcpBillingClient(null, mapper, null).fetch(7)).isEmpty();
    }

    @Test
    void importStoresEachRow() {
        GcpBillingClient client = mock(GcpBillingClient.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(client.fetch(GcpBillingImportJob.LOOKBACK_DAYS)).thenReturn(Optional.of(List.of(
                new GcpBillingClient.Row(LocalDate.of(2026, 10, 25), "Gemini API", "TTS output", "USD", 0.42, 0.42))));

        new GcpBillingImportJob(client, jdbc).importRecent();

        verify(jdbc).update(GcpBillingImportJob.UPSERT, Date.valueOf(LocalDate.of(2026, 10, 25)), "Gemini API",
                "TTS output", "USD", 0.42, 0.42);
    }
}

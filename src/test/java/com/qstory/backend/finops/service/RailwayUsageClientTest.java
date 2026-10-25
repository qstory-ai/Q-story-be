package com.qstory.backend.finops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RailwayUsageClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsCurrentUsageAndPeriod() throws Exception {
        var usage = RailwayUsageClient.parse(mapper.readTree("""
                {"data":{"workspace":{"customer":{"currentUsage":0.78,
                  "billingPeriod":{"start":"2026-10-21T06:46:53.000Z","end":"2026-11-21T06:46:53.000Z"}}}}}
                """));

        assertThat(usage).contains(new RailwayUsageClient.Usage(0.78,
                OffsetDateTime.parse("2026-10-21T06:46:53.000Z"), OffsetDateTime.parse("2026-11-21T06:46:53.000Z")));
    }

    @Test
    void notAuthorizedHasNoUsage() throws Exception {
        assertThat(RailwayUsageClient.parse(mapper.readTree("{\"errors\":[{\"message\":\"Not Authorized\"}],\"data\":null}")))
                .isEmpty();
    }

    @Test
    void disabledWithoutToken() {
        assertThat(new RailwayUsageClient(null, mapper, "", "w").fetch()).isEmpty();
    }

    @Test
    void snapshotStoresRailwayUsage() {
        OpenRouterUsageClient openRouter = mock(OpenRouterUsageClient.class);
        RailwayUsageClient railway = mock(RailwayUsageClient.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OffsetDateTime start = OffsetDateTime.parse("2026-10-21T06:46:53Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-11-21T06:46:53Z");
        when(openRouter.fetch()).thenReturn(Optional.empty());
        when(railway.fetch()).thenReturn(Optional.of(new RailwayUsageClient.Usage(0.78, start, end)));

        new ProviderCostSnapshotJob(openRouter, railway, jdbc).snapshot();

        verify(jdbc).update(eq(ProviderCostSnapshotJob.INSERT_RAILWAY), eq(0.78), eq(Timestamp.from(start.toInstant())),
                eq(Timestamp.from(end.toInstant())));
    }
}

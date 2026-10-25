package com.qstory.backend.finops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OpenRouterUsageClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsUsageAndCredits() throws Exception {
        var usage = OpenRouterUsageClient.parse(
                mapper.readTree("{\"data\":{\"usage\":11.1,\"usage_daily\":0.09,\"usage_monthly\":10.8,\"label\":\"x\"}}"),
                mapper.readTree("{\"data\":{\"total_credits\":20,\"total_usage\":11.6}}"));

        assertThat(usage).isEqualTo(new OpenRouterUsageClient.Usage(11.1, 0.09, 10.8, 20.0));
    }

    @Test
    void missingFieldsStayEmpty() throws Exception {
        var usage = OpenRouterUsageClient.parse(mapper.readTree("{\"data\":{\"usage\":1.5}}"), null);

        assertThat(usage).isEqualTo(new OpenRouterUsageClient.Usage(1.5, null, null, null));
    }

    @Test
    void snapshotSkipsWhenUsageIsUnavailable() {
        OpenRouterUsageClient client = mock(OpenRouterUsageClient.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(client.fetch()).thenReturn(Optional.empty());

        new ProviderCostSnapshotJob(client, mock(RailwayUsageClient.class), jdbc).snapshot();

        verify(jdbc, never()).update(anyString(), (Object[]) org.mockito.ArgumentMatchers.any());
    }

    @Test
    void snapshotStoresTheUsage() {
        OpenRouterUsageClient client = mock(OpenRouterUsageClient.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(client.fetch()).thenReturn(Optional.of(new OpenRouterUsageClient.Usage(11.1, 0.09, 10.8, 20.0)));

        new ProviderCostSnapshotJob(client, mock(RailwayUsageClient.class), jdbc).snapshot();

        verify(jdbc).update(ProviderCostSnapshotJob.INSERT, 11.1, 0.09, 10.8, 20.0);
    }
}

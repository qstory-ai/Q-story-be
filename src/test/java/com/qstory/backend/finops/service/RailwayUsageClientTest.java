package com.qstory.backend.finops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RailwayUsageClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void pricesUsageLikeTheRailwayBill() throws Exception {
        var usage = RailwayUsageClient.parse(mapper.readTree("""
                {"data":{
                  "usage":[{"measurement":"CPU_USAGE","value":43.02},{"measurement":"NETWORK_TX_GB","value":0.237},
                           {"measurement":"MEMORY_USAGE_GB","value":3284.09}],
                  "estimatedUsage":[{"measurement":"CPU_USAGE","estimatedValue":129.95},
                           {"measurement":"MEMORY_USAGE_GB","estimatedValue":12161.82},{"measurement":"NETWORK_TX_GB","estimatedValue":0.596}]}}
                """)).orElseThrow();

        // CLI `railway usage`: 이번 기간 $0.78, 예상 $2.90
        assertThat(usage.currentUsd()).isCloseTo(0.78, within(0.01));
        assertThat(usage.projectedUsd()).isCloseTo(2.90, within(0.05));
    }

    @Test
    void notAuthorizedHasNoUsage() throws Exception {
        assertThat(RailwayUsageClient.parse(mapper.readTree("{\"errors\":[{\"message\":\"Not Authorized\"}],\"data\":null}")))
                .isEmpty();
    }

    @Test
    void disabledWithoutToken() {
        assertThat(new RailwayUsageClient(null, mapper, "").fetch()).isEmpty();
    }

    @Test
    void snapshotStoresRailwayUsage() {
        OpenRouterUsageClient openRouter = mock(OpenRouterUsageClient.class);
        RailwayUsageClient railway = mock(RailwayUsageClient.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(openRouter.fetch()).thenReturn(Optional.empty());
        when(railway.fetch()).thenReturn(Optional.of(new RailwayUsageClient.Usage(0.78, 2.9)));

        new ProviderCostSnapshotJob(openRouter, railway, jdbc).snapshot();

        verify(jdbc).update(ProviderCostSnapshotJob.INSERT_RAILWAY, 0.78, 2.9);
    }
}

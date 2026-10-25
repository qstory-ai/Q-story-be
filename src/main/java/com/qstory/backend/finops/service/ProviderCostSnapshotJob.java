package com.qstory.backend.finops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 1시간마다 OpenRouter·Railway 사용액을 provider_cost_snapshot(098·100·101)에 남긴다 - 그라파나 "Q-Story 비용" 대시보드가 읽는다.
 * 매시 55분(UTC)에 찍어, 오늘 사용액(usage_daily)이 UTC 자정에 0이 되기 직전 값이 남게 한다. 서버가 뜰 때도 한 번 찍어
 * 대시보드가 다음 정시까지 비어 있지 않게 한다.
 */
@Component
public class ProviderCostSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(ProviderCostSnapshotJob.class);
    static final String INSERT = """
            insert into public.provider_cost_snapshot (provider, usage_total_usd, usage_daily_usd, usage_monthly_usd, credits_total_usd)
            values ('openrouter', ?, ?, ?, ?)
            """;
    static final String INSERT_RAILWAY = """
            insert into public.provider_cost_snapshot (provider, usage_monthly_usd, projected_usd)
            values ('railway', ?, ?)
            """;

    private final OpenRouterUsageClient openRouter;
    private final RailwayUsageClient railway;
    private final JdbcTemplate jdbc;

    public ProviderCostSnapshotJob(OpenRouterUsageClient openRouter, RailwayUsageClient railway, JdbcTemplate jdbc) {
        this.openRouter = openRouter;
        this.railway = railway;
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void snapshotOnStartup() {
        snapshot();
    }

    @Scheduled(cron = "0 55 * * * *", zone = "UTC")
    public void snapshot() {
        openRouter.fetch().ifPresent((usage) -> store(INSERT, usage.totalUsd(), usage.dailyUsd(), usage.monthlyUsd(),
                usage.creditsUsd()));
        railway.fetch().ifPresent((usage) -> store(INSERT_RAILWAY, usage.currentUsd(), usage.projectedUsd()));
    }

    private void store(String sql, Object... args) {
        try {
            jdbc.update(sql, args);
        } catch (RuntimeException failure) {
            log.warn("finops.snapshot-failed reason={}", failure.getClass().getSimpleName());
        }
    }
}

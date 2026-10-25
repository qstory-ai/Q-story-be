package com.qstory.backend.finops.service;

import java.sql.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 6시간마다(와 서버 시작 때) 구글 클라우드 실제 청구액을 provider_cost_daily(099)에 넣는다. 늦게 확정되는 비용이 있어 최근
 * LOOKBACK_DAYS일을 다시 읽어 덮어쓴다.
 */
@Component
public class GcpBillingImportJob {

    private static final Logger log = LoggerFactory.getLogger(GcpBillingImportJob.class);
    static final int LOOKBACK_DAYS = 7;
    static final String UPSERT = """
            insert into public.provider_cost_daily (provider, usage_date, service, sku, currency, cost, cost_usd)
            values ('gcp', ?, ?, ?, ?, ?, ?)
            on conflict (provider, usage_date, service, sku)
            do update set currency = excluded.currency, cost = excluded.cost, cost_usd = excluded.cost_usd, updated_at = now()
            """;

    private final GcpBillingClient billing;
    private final JdbcTemplate jdbc;

    public GcpBillingImportJob(GcpBillingClient billing, JdbcTemplate jdbc) {
        this.billing = billing;
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void importOnStartup() {
        importRecent();
    }

    @Scheduled(cron = "0 20 */6 * * *", zone = "UTC")
    public void importRecent() {
        billing.fetch(LOOKBACK_DAYS).ifPresent((rows) -> {
            try {
                for (GcpBillingClient.Row row : rows) {
                    jdbc.update(UPSERT, Date.valueOf(row.usageDate()), row.service(), row.sku(), row.currency(),
                            row.cost(), row.costUsd());
                }
                log.info("finops.gcp-billing-imported rows={}", rows.size());
            } catch (RuntimeException failure) {
                log.warn("finops.gcp-billing-store-failed reason={}", failure.getClass().getSimpleName());
            }
        });
    }
}

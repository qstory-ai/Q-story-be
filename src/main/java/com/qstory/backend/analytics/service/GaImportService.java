package com.qstory.backend.analytics.service;

import com.qstory.backend.analytics.repository.GaMetricsRepository;
import com.qstory.backend.config.GaProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * GA4 일일 집계를 받아 ga_daily_metrics(이벤트별)·ga_daily_traffic(방문 규모)에 넣는다. 매번 오늘(한국 시간, GA 속성
 * 시간대와 같다)을 포함한 최근 lookback-days일을 다시 받아 날짜 단위로 갈아 끼운다 - GA는 하루 이상 늦게 처리를
 * 끝내므로 어제·그제 숫자가 바뀐다. 테이블이 비어 있으면 첫 실행만 initial-backfill-days일을 받는다.
 *
 * <p>두 보고서를 모두 받은 뒤에 한 트랜잭션으로 쓴다(하나라도 실패하면 기존 행을 그대로 둔다). 실행 결과는
 * ga_import_run에 한 줄, 로그 ga-import.ok / ga-import.failed로 남긴다.
 */
@Service
public class GaImportService {

    private static final Logger log = LoggerFactory.getLogger(GaImportService.class);
    static final ZoneId GA_ZONE = ZoneId.of("Asia/Seoul");

    private final GaDataClient client;
    private final GaMetricsRepository repository;
    private final TransactionOperations transactions;
    private final GaProperties properties;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public GaImportService(
            GaDataClient client, GaMetricsRepository repository, TransactionOperations transactions,
            GaProperties properties) {
        this(client, repository, transactions, properties, Clock.systemUTC());
    }

    GaImportService(
            GaDataClient client, GaMetricsRepository repository, TransactionOperations transactions,
            GaProperties properties, Clock clock) {
        this.client = client;
        this.repository = repository;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean enabled() {
        return client.enabled();
    }

    /** 최근 며칠을 다시 받는다. 꺼져 있거나 이미 도는 중이면 아무것도 하지 않고 false. */
    public boolean importRecent() {
        if (!enabled()) {
            return false;
        }
        LocalDate today = today();
        int days = repository.isEmpty()
                ? properties.initialBackfillDaysOrDefault()
                : properties.lookbackDaysOrDefault();
        return importRange(today.minusDays(days - 1L), today);
    }

    /** GA 속성 시간대(한국 시간)의 오늘. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(GA_ZONE));
    }

    /** start~end(포함)를 받아 갈아 끼운다. 성공하면 true. */
    public boolean importRange(LocalDate start, LocalDate end) {
        if (!enabled() || !running.compareAndSet(false, true)) {
            return false;
        }
        Instant startedAt = clock.instant();
        try {
            List<GaRows.EventRow> events = client.events(start, end);
            List<GaRows.TrafficRow> traffic = client.traffic(start, end);
            Instant importedAt = clock.instant();
            transactions.executeWithoutResult(status -> {
                repository.replaceEvents(start, end, events, importedAt);
                repository.replaceTraffic(start, end, traffic, importedAt);
                repository.recordRun(startedAt, clock.instant(), start, end, true, events.size(), traffic.size(), null);
            });
            log.info("ga-import.ok start={} end={} event_rows={} traffic_rows={}", start, end, events.size(), traffic.size());
            return true;
        } catch (GaDataClient.GaApiException | RuntimeException error) {
            String reason = error instanceof GaDataClient.GaApiException
                    ? error.getMessage()
                    : error.getClass().getSimpleName();
            log.warn("ga-import.failed start={} end={} reason={}", start, end, reason);
            try {
                repository.recordRun(startedAt, clock.instant(), start, end, false, 0, 0, reason);
            } catch (RuntimeException recordFailed) {
                log.warn("ga-import.run-log-failed reason={}", recordFailed.getClass().getSimpleName());
            }
            return false;
        } finally {
            running.set(false);
        }
    }
}

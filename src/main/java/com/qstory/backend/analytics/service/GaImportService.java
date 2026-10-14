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
 * 끝내므로 어제·그제 숫자가 바뀐다. 그 속성 행이 아직 없으면 첫 실행만 initial-backfill-days일을 받는다.
 * 속성이 여럿이면(랜딩 속성과 웹·앱 속성) 하나씩 따로 받고 property_id로 구분해 넣는다.
 *
 * <p>속성마다 두 보고서를 모두 받은 뒤에 한 트랜잭션으로 쓴다(하나라도 실패하면 그 속성의 기존 행을 그대로 둔다).
 * 실행 결과는 속성마다 ga_import_run에 한 줄, 로그 ga-import.ok / ga-import.failed로 남긴다.
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

    /**
     * 속성마다 최근 며칠을 다시 받는다(그 속성 행이 아직 없으면 백필). 꺼져 있거나 이미 도는 중이면 아무것도 하지 않고
     * false. 한 속성이 실패해도 나머지는 받는다 - 모두 성공해야 true.
     */
    public boolean importRecent() {
        if (!enabled() || !running.compareAndSet(false, true)) {
            return false;
        }
        try {
            LocalDate today = today();
            boolean allOk = true;
            for (String property : client.propertyIds()) {
                int days;
                try {
                    days = repository.isEmpty(property)
                            ? properties.initialBackfillDaysOrDefault()
                            : properties.lookbackDaysOrDefault();
                } catch (RuntimeException dbDown) {
                    log.warn("ga-import.failed property={} reason={}", property, dbDown.getClass().getSimpleName());
                    allOk = false;
                    continue;
                }
                allOk &= importOne(property, today.minusDays(days - 1L), today);
            }
            return allOk;
        } finally {
            running.set(false);
        }
    }

    /** GA 속성 시간대(한국 시간)의 오늘. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(GA_ZONE));
    }

    /** 모든 속성의 start~end(포함)를 받아 갈아 끼운다. 모두 성공하면 true. */
    public boolean importRange(LocalDate start, LocalDate end) {
        if (!enabled() || !running.compareAndSet(false, true)) {
            return false;
        }
        try {
            boolean allOk = true;
            for (String property : client.propertyIds()) {
                allOk &= importOne(property, start, end);
            }
            return allOk;
        } finally {
            running.set(false);
        }
    }

    /** 한 속성의 두 보고서를 모두 받은 뒤 한 트랜잭션으로 갈아 끼운다. 실패하면 기존 행을 그대로 둔다. */
    private boolean importOne(String property, LocalDate start, LocalDate end) {
        Instant startedAt = clock.instant();
        try {
            List<GaRows.EventRow> events = client.events(property, start, end);
            List<GaRows.TrafficRow> traffic = client.traffic(property, start, end);
            Instant importedAt = clock.instant();
            transactions.executeWithoutResult(status -> {
                repository.replaceEvents(property, start, end, events, importedAt);
                repository.replaceTraffic(property, start, end, traffic, importedAt);
                repository.recordRun(
                        property, startedAt, clock.instant(), start, end, true, events.size(), traffic.size(), null);
            });
            log.info("ga-import.ok property={} start={} end={} event_rows={} traffic_rows={}",
                    property, start, end, events.size(), traffic.size());
            return true;
        } catch (GaDataClient.GaApiException | RuntimeException error) {
            String reason = error instanceof GaDataClient.GaApiException
                    ? error.getMessage()
                    : error.getClass().getSimpleName();
            log.warn("ga-import.failed property={} start={} end={} reason={}", property, start, end, reason);
            try {
                repository.recordRun(property, startedAt, clock.instant(), start, end, false, 0, 0, reason);
            } catch (RuntimeException recordFailed) {
                log.warn("ga-import.run-log-failed reason={}", recordFailed.getClass().getSimpleName());
            }
            return false;
        }
    }
}

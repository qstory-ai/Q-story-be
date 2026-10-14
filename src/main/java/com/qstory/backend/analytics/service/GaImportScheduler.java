package com.qstory.backend.analytics.service;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * GA 가져오기를 한국 시간 새벽 두 번(04:40, 07:40) 돌리고, 부팅 2분 뒤에도 한 번 돌린다(배포 직후 바로 확인되게,
 * 테이블이 비어 있으면 첫 백필). GA 처리가 하루 넘게 걸리므로 두 번째 실행이 첫 실행 뒤 늘어난 숫자를 더 받는다.
 *
 * <p>실제 호출은 전용 스레드 하나에서 한다. @Scheduled 스레드는 앱 전체가 하나를 같이 쓰므로(app.heartbeat 포함)
 * GA 응답을 기다리느라 다른 정기 작업이 밀리지 않게 넘기기만 한다.
 */
@Component
public class GaImportScheduler {

    private static final Logger log = LoggerFactory.getLogger(GaImportScheduler.class);
    private static final long STARTUP_DELAY_SECONDS = 120;

    private final GaImportService service;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ga-import");
        thread.setDaemon(true);
        return thread;
    });

    public GaImportScheduler(GaImportService service) {
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void afterStartup() {
        if (service.enabled()) {
            submit(STARTUP_DELAY_SECONDS);
        }
    }

    @Scheduled(cron = "0 40 4,7 * * *", zone = "Asia/Seoul")
    public void daily() {
        if (service.enabled()) {
            submit(0);
        }
    }

    private void submit(long delaySeconds) {
        try {
            worker.schedule(this::runOnce, delaySeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            // 종료 중 - 다음 부팅·다음 정기 실행이 받는다.
        }
    }

    private void runOnce() {
        try {
            service.importRecent();
        } catch (RuntimeException unexpected) {
            log.warn("ga-import.failed reason={}", unexpected.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }
}

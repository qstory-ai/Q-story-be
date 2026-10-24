package com.qstory.backend.betaevents.service;

import com.qstory.backend.betaevents.util.BetaEventValidator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 베타 이벤트를 메모리 대기열에 받아 두고, 전용 스레드 하나가 최대 100건씩 묶어 한 트랜잭션으로 저장한다.
 *
 * <p>이벤트가 몰릴 때 요청마다 DB에 바로 쓰면 DB 연결이 적어 다른 요청(로그인 확인, 회차 완료 등)까지 연결을 기다리며 밀린다.
 * 그래서 요청 스레드는 검증·한도 확인 뒤 대기열에 넣고 바로 202로 답하고, 베타 이벤트는 한 번에 DB 연결 하나만 쓴다.
 * 대기열(5,000건)이 차면 그 이벤트는 버린다 - 분석용이라 조금 잃어도 괜찮고, 버린 수는 1분에 한 번만 WARN으로 남긴다.
 * 앱이 내려갈 때는 남은 이벤트를 마저 저장한다.
 */
@Component
public class BetaEventIngestQueue {

    private static final Logger log = LoggerFactory.getLogger(BetaEventIngestQueue.class);

    static final int CAPACITY = 5_000;
    static final int BATCH_SIZE = 100;
    static final long DROP_LOG_INTERVAL_MS = 60_000;
    private static final long POLL_WAIT_MS = 1_000;
    private static final long SHUTDOWN_WAIT_MS = 10_000;

    /** 요청에서 받은 이벤트 한 건. receivedAt은 요청을 받은 시각(대기열에서 기다린 시간은 빼고 남긴다). */
    public record Pending(BetaEventValidator.ParsedEvent event, UUID userId, Instant receivedAt) {}

    private final BlockingQueue<Pending> queue;
    private final Consumer<List<Pending>> writer;
    private final int batchSize;
    private final LongSupplier clock;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong droppedSinceLog = new AtomicLong();
    private final AtomicLong lastDropLogAt = new AtomicLong(Long.MIN_VALUE);
    private final Thread worker;
    private volatile boolean running = true;

    @Autowired
    public BetaEventIngestQueue(BetaEventService service) {
        this(service::storeBatch, CAPACITY, BATCH_SIZE, System::currentTimeMillis);
    }

    BetaEventIngestQueue(Consumer<List<Pending>> writer, int capacity, int batchSize, LongSupplier clock) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.writer = writer;
        this.batchSize = batchSize;
        this.clock = clock;
        this.worker = new Thread(this::runLoop, "beta-events-writer");
        this.worker.setDaemon(true);
    }

    @PostConstruct
    void start() {
        worker.start();
    }

    /** 대기열에 넣는다. 가득 차 있으면 버리고 false. 요청 스레드는 절대 기다리지 않는다. */
    public boolean offer(Pending pending) {
        if (queue.offer(pending)) {
            return true;
        }
        dropped.incrementAndGet();
        droppedSinceLog.incrementAndGet();
        long now = clock.getAsLong();
        if (shouldLogDrop(now)) {
            log.warn("beta-events.dropped reason=queue-full count={} total={}",
                    droppedSinceLog.getAndSet(0), dropped.get());
        }
        return false;
    }

    /** 마지막 WARN 뒤로 1분이 지났을 때만 true - 여러 스레드가 동시에 버려도 한 번만 남긴다. */
    boolean shouldLogDrop(long now) {
        long last = lastDropLogAt.get();
        if (last != Long.MIN_VALUE && now - last < DROP_LOG_INTERVAL_MS) {
            return false;
        }
        return lastDropLogAt.compareAndSet(last, now);
    }

    long droppedCount() {
        return dropped.get();
    }

    int size() {
        return queue.size();
    }

    private void runLoop() {
        while (running) {
            try {
                drainOnce(POLL_WAIT_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 첫 건을 waitMs까지 기다렸다가, 그때 쌓인 것까지 batchSize만큼 묶어 저장한다. 저장한 건수를 돌려준다. */
    int drainOnce(long waitMs) throws InterruptedException {
        Pending first = queue.poll(waitMs, TimeUnit.MILLISECONDS);
        if (first == null) {
            return 0;
        }
        List<Pending> batch = new ArrayList<>(batchSize);
        batch.add(first);
        queue.drainTo(batch, batchSize - 1);
        write(batch);
        return batch.size();
    }

    private void write(List<Pending> batch) {
        try {
            writer.accept(batch);
        } catch (RuntimeException failure) {
            // 저장이 실패해도 스레드는 살아 있어야 다음 묶음을 받는다.
            log.warn("beta-events.batch-failed count={} reason={}", batch.size(), failure.getClass().getSimpleName());
        }
    }

    /** 앱이 내려갈 때 - 지금 묶음이 끝나길 기다린 뒤 남은 이벤트를 마저 저장한다. */
    @PreDestroy
    void shutdown() {
        running = false;
        try {
            worker.join(SHUTDOWN_WAIT_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        flushRemaining();
    }

    void flushRemaining() {
        List<Pending> batch = new ArrayList<>(batchSize);
        while (queue.drainTo(batch, batchSize) > 0) {
            write(batch);
            batch = new ArrayList<>(batchSize);
        }
    }
}

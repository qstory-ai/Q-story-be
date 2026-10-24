package com.qstory.backend.betaevents.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.qstory.backend.betaevents.util.BetaEventValidator;
import com.qstory.backend.common.enums.EventName;
import com.qstory.backend.common.enums.EventSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 베타 이벤트 대기열 - 요청은 넣기만 하고, 저장 스레드 하나가 묶음으로 저장한다. 차면 버리고, 내려갈 때 남은 것을 저장한다. */
class BetaEventIngestQueueTest {

    private final List<List<BetaEventIngestQueue.Pending>> written = new CopyOnWriteArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000_000);

    private BetaEventIngestQueue queue(int capacity, int batchSize) {
        return new BetaEventIngestQueue(batch -> written.add(List.copyOf(batch)), capacity, batchSize, now::get);
    }

    private static BetaEventIngestQueue.Pending pending() {
        return new BetaEventIngestQueue.Pending(
                new BetaEventValidator.ParsedEvent(
                        UUID.randomUUID(), UUID.randomUUID(), EventName.APP_ENTRY, EventSource.APP, Instant.now(),
                        Map.of()),
                null, Instant.now());
    }

    @Test
    void drainsUpToBatchSizePerWrite() throws InterruptedException {
        BetaEventIngestQueue queue = queue(1_000, 100);
        for (int i = 0; i < 250; i++) {
            assertThat(queue.offer(pending())).isTrue();
        }

        assertThat(queue.drainOnce(0)).isEqualTo(100);
        assertThat(queue.drainOnce(0)).isEqualTo(100);
        assertThat(queue.drainOnce(0)).isEqualTo(50);
        assertThat(queue.drainOnce(0)).isZero();

        assertThat(written).extracting(List::size).containsExactly(100, 100, 50);
    }

    @Test
    void dropsWhenFullAndCountsDrops() {
        BetaEventIngestQueue queue = queue(3, 100);
        for (int i = 0; i < 3; i++) {
            assertThat(queue.offer(pending())).isTrue();
        }

        assertThat(queue.offer(pending())).isFalse();
        assertThat(queue.offer(pending())).isFalse();

        assertThat(queue.droppedCount()).isEqualTo(2);
        assertThat(queue.size()).isEqualTo(3);
        assertThat(written).isEmpty();
    }

    @Test
    void dropWarningAtMostOncePerMinute() {
        BetaEventIngestQueue queue = queue(1, 100);

        assertThat(queue.shouldLogDrop(now.get())).isTrue();
        assertThat(queue.shouldLogDrop(now.get() + 1_000)).isFalse();
        assertThat(queue.shouldLogDrop(now.get() + 59_999)).isFalse();
        assertThat(queue.shouldLogDrop(now.get() + 60_000)).isTrue();
        assertThat(queue.shouldLogDrop(now.get() + 61_000)).isFalse();
    }

    @Test
    void writerFailureDoesNotStopTheNextBatch() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        BetaEventIngestQueue queue = new BetaEventIngestQueue(batch -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("db down");
            }
            written.add(List.copyOf(batch));
        }, 10, 1, now::get);
        queue.offer(pending());
        queue.offer(pending());

        assertThat(queue.drainOnce(0)).isEqualTo(1);
        assertThat(queue.drainOnce(0)).isEqualTo(1);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(written).hasSize(1);
    }

    @Test
    void workerThreadWritesInBackgroundAndShutdownFlushesTheRest() {
        BetaEventIngestQueue queue = queue(1_000, 100);
        queue.start();
        List<BetaEventIngestQueue.Pending> sent = new ArrayList<>();
        for (int i = 0; i < 230; i++) {
            BetaEventIngestQueue.Pending one = pending();
            sent.add(one);
            queue.offer(one);
        }

        queue.shutdown();

        List<BetaEventIngestQueue.Pending> all = written.stream().flatMap(List::stream).toList();
        assertThat(all).containsExactlyInAnyOrderElementsOf(sent);
        assertThat(written).allSatisfy(batch -> assertThat(batch.size()).isLessThanOrEqualTo(100));
        assertThat(queue.size()).isZero();
    }

    @Test
    void writesComeFromASingleThread() {
        List<String> threads = new CopyOnWriteArrayList<>();
        BetaEventIngestQueue queue = new BetaEventIngestQueue(
                batch -> threads.add(Thread.currentThread().getName()), 1_000, 5, now::get);
        queue.start();
        for (int i = 0; i < 50; i++) {
            queue.offer(pending());
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (queue.size() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        queue.shutdown();

        // 저장이 겹치지 않으므로 베타 이벤트가 동시에 잡는 DB 연결은 하나뿐이다.
        assertThat(threads).isNotEmpty().allMatch(name -> name.equals("beta-events-writer")
                || name.equals(Thread.currentThread().getName()));
    }
}

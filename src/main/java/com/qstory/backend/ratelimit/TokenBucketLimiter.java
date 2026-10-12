package com.qstory.backend.ratelimit;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 메모리 토큰 버킷. 버킷마다 capacity개로 시작해 window마다 capacity개가 고르게 다시 찬다 - 쉬다 오면 capacity번까지
 * 몰아 쓸 수 있고, 꾸준히 보내면 window당 capacity번이다. Railway에서 인스턴스 하나로 돌기 때문에 메모리로 충분하다
 * (여러 대로 늘리면 인스턴스마다 따로 세므로 상한이 그 배수가 된다). 재시작하면 모두 가득 찬 상태로 돌아간다.
 *
 * <p>기억하는 버킷 수가 maxKeys를 넘으면 가득 찬 버킷(그동안 안 쓴 것과 같다)을 지운다. 그래도 넘치면 새 키는 기억하지
 * 않고 허용한다 - 주소를 바꿔 가며 메모리를 채우는 쪽은 GLOBAL 상한이 막는다.
 */
final class TokenBucketLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int maxKeys;

    TokenBucketLimiter(int maxKeys) {
        this.maxKeys = maxKeys;
    }

    /**
     * 한 번 쓴다. 허용하면 0, 막으면 다시 해 볼 수 있을 때까지의 초(1 이상).
     *
     * @param nowNanos 단조 증가하는 시각(System.nanoTime) - 테스트에서 고정한다.
     */
    long tryAcquire(String key, int capacity, long windowNanos, long nowNanos) {
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= maxKeys) {
                evictFull(nowNanos);
                if (buckets.size() >= maxKeys) return 0;
            }
            bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(capacity, nowNanos));
        }
        return bucket.tryAcquire(capacity, windowNanos, nowNanos);
    }

    /** 쓰지 않고 본다 - 지금 한 번 쓸 수 있으면 0, 비었으면 다시 찰 때까지의 초. 모르는 키는 가득 찬 것으로 본다. */
    long peek(String key, int capacity, long windowNanos, long nowNanos) {
        Bucket bucket = buckets.get(key);
        return bucket == null ? 0 : bucket.peek(capacity, windowNanos, nowNanos);
    }

    int size() {
        return buckets.size();
    }

    void clear() {
        buckets.clear();
    }

    private void evictFull(long nowNanos) {
        for (Iterator<Bucket> it = buckets.values().iterator(); it.hasNext(); ) {
            if (it.next().isFull(nowNanos)) it.remove();
        }
    }

    private static final class Bucket {
        private double tokens;
        private long updatedAt;
        private double lastCapacity;
        private double lastRefillPerNano;

        Bucket(int capacity, long nowNanos) {
            this.tokens = capacity;
            this.updatedAt = nowNanos;
            this.lastCapacity = capacity;
        }

        synchronized long tryAcquire(int capacity, long windowNanos, long nowNanos) {
            double refillPerNano = (double) capacity / windowNanos;
            refill(capacity, refillPerNano, nowNanos);
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return waitSeconds(refillPerNano);
        }

        private long waitSeconds(double refillPerNano) {
            double waitNanos = (1 - tokens) / refillPerNano;
            return Math.max(1, (long) Math.ceil(waitNanos / NANOS_PER_SECOND));
        }

        synchronized long peek(int capacity, long windowNanos, long nowNanos) {
            double refillPerNano = (double) capacity / windowNanos;
            refill(capacity, refillPerNano, nowNanos);
            return tokens >= 1 ? 0 : waitSeconds(refillPerNano);
        }

        synchronized boolean isFull(long nowNanos) {
            refill(lastCapacity, lastRefillPerNano, nowNanos);
            return tokens >= lastCapacity;
        }

        private void refill(double capacity, double refillPerNano, long nowNanos) {
            long elapsed = Math.max(0, nowNanos - updatedAt);
            tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
            updatedAt = nowNanos;
            lastCapacity = capacity;
            lastRefillPerNano = refillPerNano;
        }
    }
}

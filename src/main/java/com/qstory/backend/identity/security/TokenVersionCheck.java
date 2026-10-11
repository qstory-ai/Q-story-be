package com.qstory.backend.identity.security;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 토큰이 아직 유효한 계정의 것인지(078) - 토큰의 tv가 app_user.token_version과 같고 계정이 탈퇴하지 않았어야 한다. 비밀번호
 * 변경·재설정·탈퇴는 token_version을 올려 그 전에 발급한 토큰을 모두 무효로 만든다.
 *
 * <p>요청마다 DB를 읽지 않게 계정별 결과를 30초 둔다. 이 서버 인스턴스에서 올린 값은 바로 반영되고(evict), 다른 인스턴스에서
 * 올린 값은 길어야 30초 뒤에 반영된다.
 */
@Component
public class TokenVersionCheck {

    static final Duration TTL = Duration.ofSeconds(30);
    static final int MAX_ENTRIES = 10_000;

    private record Entry(Integer version, long expiresAtNanos) {}

    private final JdbcTemplate jdbc;
    private final LongSupplier nanoClock;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    @Autowired
    public TokenVersionCheck(JdbcTemplate jdbc) {
        this(jdbc, System::nanoTime);
    }

    TokenVersionCheck(JdbcTemplate jdbc, LongSupplier nanoClock) {
        this.jdbc = jdbc;
        this.nanoClock = nanoClock;
    }

    /** 토큰의 계정이 있고 탈퇴하지 않았으며 토큰 버전이 지금 계정 값과 같으면 true. */
    public boolean isCurrent(CurrentUser user) {
        Integer version = currentVersion(user.userId());
        return version != null && version == user.tokenVersion();
    }

    /**
     * 계정의 토큰 버전을 올린 뒤 부른다 - 이 인스턴스의 캐시를 비운다. 트랜잭션 안이면 커밋 뒤에 한 번 더 비운다(커밋 전에
     * 다른 요청이 옛 값을 다시 캐시했을 수 있다).
     */
    public void evict(UUID userId) {
        cache.remove(userId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.remove(userId);
                }
            });
        }
    }

    /** 탈퇴했거나 없는 계정이면 null. */
    private Integer currentVersion(UUID userId) {
        long now = nanoClock.getAsLong();
        Entry cached = cache.get(userId);
        if (cached != null && now - cached.expiresAtNanos() < 0) {
            return cached.version();
        }
        List<Integer> rows = jdbc.queryForList(
                "select token_version from app_user where id = ? and deleted_at is null", Integer.class, userId);
        Integer version = rows.isEmpty() ? null : rows.get(0);
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(userId, new Entry(version, now + TTL.toNanos()));
        return version;
    }
}

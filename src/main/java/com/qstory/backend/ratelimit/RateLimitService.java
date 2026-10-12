package com.qstory.backend.ratelimit;

import com.qstory.backend.common.util.DigestUtil;
import com.qstory.backend.identity.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * 요청 상한(보안 점검 M1/M2). 경로로 정해지는 상한은 RateLimitFilter가, 본문을 읽어야 정해지는 상한(로그인 아이디,
 * TTS 미리 만들기 여부)과 틀린 X-Admin-Token은 각 컨트롤러·가드가 이 서비스로 센다.
 *
 * <p>키: 로그인했으면 계정(u:&lt;userId&gt;), 아니면 믿을 수 있는 클라이언트 주소(ip:&lt;addr&gt;, ClientIpResolver).
 * 로그에는 키 원문(주소) 대신 SHA-256 앞 12자리만 남긴다.
 */
@Service
public class RateLimitService {

    public static final String CLIENT_IP_ATTRIBUTE = "qstoryClientIp";

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    /** 공격 중 로그가 쏟아지지 않게 1분에 이만큼만 찍고 나머지는 다음 줄의 suppressed=로 센다. */
    private static final int MAX_BLOCK_LOGS_PER_MINUTE = 300;

    private final RateLimitProperties properties;
    private final ClientIpResolver ipResolver;
    private final TokenBucketLimiter limiter;
    private final DailyQuota anonymousTtsDaily = new DailyQuota();
    private final Clock clock;
    private final LongSupplier nanoTime;

    private final AtomicLong logWindowStartedAt = new AtomicLong();
    private final AtomicInteger logsInWindow = new AtomicInteger();
    private final AtomicInteger suppressedLogs = new AtomicInteger();

    @Autowired
    public RateLimitService(RateLimitProperties properties) {
        this(properties, Clock.systemUTC(), System::nanoTime);
    }

    RateLimitService(RateLimitProperties properties, Clock clock, LongSupplier nanoTime) {
        this.properties = properties;
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.ipResolver = new ClientIpResolver(properties.proxySharedSecret(), properties.clientIpHeader(), clock);
        this.limiter = new TokenBucketLimiter(properties.maxTrackedKeys());
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /** 이 요청의 클라이언트 주소 - 한 번 정하면 요청 속성에 둔다. */
    public String clientIp(HttpServletRequest request) {
        Object cached = request.getAttribute(CLIENT_IP_ATTRIBUTE);
        if (cached instanceof String ip) return ip;
        String ip = ipResolver.resolve(request);
        request.setAttribute(CLIENT_IP_ATTRIBUTE, ip);
        return ip;
    }

    /** 로그인했으면 계정, 아니면 주소. */
    public String clientKey(HttpServletRequest request) {
        CurrentUser user = currentUser();
        return user != null ? "u:" + user.userId() : "ip:" + clientIp(request);
    }

    /** rule의 범위(scope)에 맞는 키로 한 번 센다. 막히면 RateLimitedException. */
    public void enforce(HttpServletRequest request, RateLimitRule rule) {
        if (!properties.enabled()) return;
        long retryAfter = check(request, rule);
        if (retryAfter > 0) throw new RateLimitedException(rule, retryAfter);
    }

    /** 키를 직접 정해 센다(로그인 아이디처럼 본문에서 나온 값). 주소별 키는 clientIp와 묶어 넘긴다. */
    public void enforce(RateLimitRule rule, String key) {
        if (!properties.enabled()) return;
        long retryAfter = acquire(rule, key);
        if (retryAfter > 0) throw new RateLimitedException(rule, retryAfter);
    }

    /**
     * 틀린 비밀(X-Admin-Token 등) 대입 막기 - 맞든 틀리든 비교하기 전에 부른다. 이 클라이언트가 이미 상한만큼 틀렸으면
     * 비교하지 않고 429(맞는 값을 보내도) - 그래야 상한 뒤의 시도로 맞혔는지 알 수 없다.
     */
    public void requireNotBlocked(HttpServletRequest request, RateLimitRule rule) {
        if (!properties.enabled()) return;
        long retryAfter = limiter.peek(rule.id() + "|" + clientKey(request), properties.requestsFor(rule),
                properties.windowFor(rule).toNanos(), nanoTime.getAsLong());
        if (retryAfter > 0) {
            logBlocked(rule.id(), clientKey(request));
            throw new RateLimitedException(rule, retryAfter);
        }
    }

    /** 틀린 시도 하나를 센다(requireNotBlocked와 짝). 상한에 닿아도 이번 응답은 원래 실패(403 등) 그대로 둔다. */
    public void recordFailure(HttpServletRequest request, RateLimitRule rule) {
        if (!properties.enabled()) return;
        limiter.tryAcquire(rule.id() + "|" + clientKey(request), properties.requestsFor(rule),
                properties.windowFor(rule).toNanos(), nanoTime.getAsLong());
    }

    /**
     * 기본 Gemini 키를 쓰는 실시간 TTS(미리 만들기가 아닌 것) - 로그인하지 않은 호출만 서버 전체 1시간·하루 상한을 함께 센다.
     * 하루 한도(100회)를 데모 실시간 음성과 나눠 쓰므로, 익명 호출이 다 써 버려도 로그인한 UT 사용자 몫이 남게 한다.
     */
    public void enforceAnonymousLiveTts(HttpServletRequest request) {
        if (!properties.enabled() || currentUser() != null) return;
        enforce(request, RateLimitRule.TTS_ANONYMOUS_HOURLY);
        int daily = properties.ttsAnonymousDaily();
        if (daily <= 0) return;
        long retryAfter = anonymousTtsDaily.tryAcquire(daily, clock.instant());
        if (retryAfter > 0) {
            logBlocked("tts-anonymous-daily", "global");
            throw new RateLimitedException(RateLimitRule.TTS_ANONYMOUS_HOURLY, retryAfter);
        }
    }

    /** 범위에 맞는 키로 한 번 센다. 허용하면 0, 막으면 Retry-After 초. GLOBAL_ANONYMOUS는 로그인한 호출이면 세지 않는다. */
    long check(HttpServletRequest request, RateLimitRule rule) {
        return switch (rule.scope()) {
            case PER_CLIENT -> acquire(rule, clientKey(request));
            case GLOBAL -> acquire(rule, "global");
            case GLOBAL_ANONYMOUS -> currentUser() != null ? 0 : acquire(rule, "global");
        };
    }

    long acquire(RateLimitRule rule, String key) {
        long retryAfter = limiter.tryAcquire(
                rule.id() + "|" + key,
                properties.requestsFor(rule),
                properties.windowFor(rule).toNanos(),
                nanoTime.getAsLong());
        if (retryAfter > 0) logBlocked(rule.id(), key);
        return retryAfter;
    }

    void reset() {
        limiter.clear();
    }

    private void logBlocked(String group, String key) {
        long now = clock.millis();
        long started = logWindowStartedAt.get();
        if (now - started >= 60_000 && logWindowStartedAt.compareAndSet(started, now)) {
            logsInWindow.set(0);
        }
        if (logsInWindow.incrementAndGet() > MAX_BLOCK_LOGS_PER_MINUTE) {
            suppressedLogs.incrementAndGet();
            return;
        }
        log.warn("rate-limit.blocked group={} key={} suppressed={}", group, hashKey(key), suppressedLogs.getAndSet(0));
    }

    /** 로그용 키 - 주소·계정 id 원문을 남기지 않는다. 같은 키는 같은 값이라 한 클라이언트가 반복하는지는 보인다. */
    static String hashKey(String key) {
        if ("global".equals(key)) return "global";
        return DigestUtil.sha256Hex(key).substring(0, 12);
    }

    private static CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CurrentUser user) {
            return user;
        }
        return null;
    }

    /** 이용자에게 보여 줄 문구. 아이가 보는 대화(그레텔)는 반말, 어른이 보는 화면은 존댓말. */
    static String safeDetail(RateLimitRule rule, long retryAfterSeconds) {
        String wait = waitPhrase(retryAfterSeconds);
        return switch (rule) {
            case TTS, TTS_ANONYMOUS_HOURLY, COMPANION_CHAT, COMPANION_CHAT_GLOBAL, STT, STT_GLOBAL,
                    QUESTION_ROUTE, QUESTION_ROUTE_GLOBAL -> "그레텔이 잠깐 쉬고 있어. 잠시 후에 다시 말해 줘.";
            case LOGIN_CLIENT, LOGIN_ACCOUNT, LOGIN_ID ->
                    "로그인 시도가 너무 많아요. " + wait + " 다시 시도해 주세요.";
            case SIGNUP, SIGNUP_STAFF -> "가입 요청이 너무 많아요. " + wait + " 다시 시도해 주세요.";
            case PASSWORD_RESET_REQUEST, PASSWORD_RESET_CONFIRM, PASSWORD_CHANGE, ADMIN_TOKEN_FAILURE ->
                    "요청이 너무 많아요. " + wait + " 다시 시도해 주세요.";
            default -> "요청이 너무 잦아요. 잠시 후 다시 시도해 주세요.";
        };
    }

    private static String waitPhrase(long seconds) {
        if (seconds < 60) return "잠시 후";
        long minutes = (seconds + 59) / 60;
        if (minutes < 60) return String.format(Locale.ROOT, "%d분 뒤에", minutes);
        return String.format(Locale.ROOT, "%d시간 뒤에", (minutes + 59) / 60);
    }
}

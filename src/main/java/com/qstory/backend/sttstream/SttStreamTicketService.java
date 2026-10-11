package com.qstory.backend.sttstream;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.SecureTokenGenerator;
import com.qstory.backend.config.SttStreamProperties;
import com.qstory.backend.conversationrecord.ConversationAttribution;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 실시간 받아 적기 표 발급·사용과 RTZR 스트리밍 채널 수 관리. 메모리에만 둔다 - 표는 30초 안에 한 번 쓰고 버리는
 * 것이라 서버가 다시 뜨면 앱이 배치 받아 적기로 넘어가면 그만이다(서버가 여러 대가 되면 표를 준 서버와 연결을
 * 받는 서버가 달라질 수 있다 - 그때는 이 저장소를 공유 저장소로 옮긴다).
 *
 * <p>상한: 대화(sessionId) 하나당 10분에 sessionTicketLimit장, 서버 전체 1분에 globalTicketsPerMinute장, 동시 채널
 * maxConcurrent개. sessionId는 앱이 만드는 값이라 바꿔 가며 우회할 수 있으므로 전체 상한·동시 채널 상한이 실제 비용 상한이다.
 */
@Service
public class SttStreamTicketService {

    static final Duration SESSION_WINDOW = Duration.ofMinutes(10);
    static final Duration GLOBAL_WINDOW = Duration.ofMinutes(1);

    private final SttStreamProperties properties;
    private final SecureTokenGenerator tokenGenerator;
    private final Clock clock;
    private final Semaphore channels;
    private final Map<String, SttStreamTicket> tickets = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> sessionIssues = new ConcurrentHashMap<>();
    private final Deque<Instant> globalIssues = new ArrayDeque<>();

    @Autowired
    public SttStreamTicketService(SttStreamProperties properties, SecureTokenGenerator tokenGenerator) {
        this(properties, tokenGenerator, Clock.systemUTC());
    }

    SttStreamTicketService(SttStreamProperties properties, SecureTokenGenerator tokenGenerator, Clock clock) {
        this.properties = properties;
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
        this.channels = new Semaphore(properties.maxConcurrent());
    }

    public SttStreamTicket issue(
            String storyId, String sceneId, List<String> sttKeywords, ConversationAttribution attribution) {
        if (!properties.enabled()) {
            throw unavailable("지금은 실시간 받아 적기를 쓰지 않아요.");
        }
        if (channels.availablePermits() <= 0) {
            throw unavailable("지금은 실시간 받아 적기가 붐벼요.");
        }
        Instant now = clock.instant();
        String sessionKey = attribution.sessionId() == null ? "anonymous" : attribution.sessionId().toString();
        synchronized (this) {
            purgeExpired(now);
            Deque<Instant> session = sessionIssues.computeIfAbsent(sessionKey, key -> new ArrayDeque<>());
            trim(session, now.minus(SESSION_WINDOW));
            trim(globalIssues, now.minus(GLOBAL_WINDOW));
            if (session.size() >= properties.sessionTicketLimit() || globalIssues.size() >= properties.globalTicketsPerMinute()) {
                throw ApiException.contractError(ErrorCode.RATE_LIMITED, "잠시 후 다시 말해 주세요.");
            }
            session.addLast(now);
            globalIssues.addLast(now);
        }
        SttStreamTicket ticket = new SttStreamTicket(
                tokenGenerator.generate(), storyId, sceneId, sttKeywords == null ? List.of() : List.copyOf(sttKeywords),
                attribution, now.plusMillis(properties.ticketTtlMs()));
        tickets.put(ticket.id(), ticket);
        return ticket;
    }

    /** 표를 쓴다 - 한 번만 쓸 수 있고, 만료됐거나 모르는 표면 비어 있다. */
    public Optional<SttStreamTicket> redeem(String ticketId) {
        if (ticketId == null || ticketId.isBlank() || !properties.enabled()) {
            return Optional.empty();
        }
        SttStreamTicket ticket = tickets.remove(ticketId);
        if (ticket == null || ticket.expired(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(ticket);
    }

    /** RTZR 스트리밍 채널 하나를 잡는다 - 다 쓰고 있으면 false(앱은 배치로 간다). */
    public boolean tryAcquireChannel() {
        return channels.tryAcquire();
    }

    public void releaseChannel() {
        channels.release();
    }

    int availableChannels() {
        return channels.availablePermits();
    }

    public SttStreamProperties properties() {
        return properties;
    }

    private void purgeExpired(Instant now) {
        tickets.values().removeIf(ticket -> ticket.expired(now));
        sessionIssues.entrySet().removeIf(entry -> {
            trim(entry.getValue(), now.minus(SESSION_WINDOW));
            return entry.getValue().isEmpty();
        });
    }

    private static void trim(Deque<Instant> issues, Instant cutoff) {
        while (!issues.isEmpty() && issues.peekFirst().isBefore(cutoff)) {
            issues.removeFirst();
        }
    }

    private static ApiException unavailable(String detail) {
        return ApiException.contractError(ErrorCode.STT_STREAM_UNAVAILABLE, detail);
    }
}

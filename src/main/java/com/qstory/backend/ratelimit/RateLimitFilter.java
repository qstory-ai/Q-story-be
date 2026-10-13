package com.qstory.backend.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.error.Failure;
import com.qstory.backend.common.error.FailureBody;
import com.qstory.backend.common.util.HttpJsonWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 경로로 정해지는 요청 상한(보안 점검 M1/M2). Spring Security 필터(JwtAuthFilter) 뒤에 돌아 로그인한 요청은 계정으로 센다.
 * 걸리면 컨트롤러에 닿기 전에 429 + Retry-After + 실패 봉투로 끊는다. 한 경로에 상한이 여럿이면(주소별 + 서버 전체) 앞에서부터
 * 보고 처음 걸린 것으로 답한다.
 *
 * <p>본문을 읽어야 정해지는 상한은 여기서 보지 않는다 - 로그인 아이디(AuthController), 실시간 TTS 익명 총량
 * (NarrationController, 미리 만들기는 전용 키라 빼고 센다), 틀린 X-Admin-Token(AdminTokenGuard).
 */
@Component
@Order(3)
public class RateLimitFilter extends OncePerRequestFilter {

    record Route(String method, Pattern path, List<RateLimitRule> rules) {
        Route(String method, String path, RateLimitRule... rules) {
            this(method, Pattern.compile(path), List.of(rules));
        }
    }

    private static final String UUID = "[0-9a-fA-F-]{36}";

    static final List<Route> ROUTES = List.of(
            // 돈·한도를 쓰는 호출
            new Route("POST", "^/v1/narrations(/stream)?$", RateLimitRule.TTS),
            new Route("POST", "^/v1/companion-chat/messages$",
                    RateLimitRule.COMPANION_CHAT, RateLimitRule.COMPANION_CHAT_GLOBAL),
            new Route("POST", "^/v1/(transcriptions(/base64)?|companion-chat/transcriptions/base64|stt/stream-tickets)$",
                    RateLimitRule.STT, RateLimitRule.STT_GLOBAL),
            new Route("POST", "^/v1/questions/route$",
                    RateLimitRule.QUESTION_ROUTE, RateLimitRule.QUESTION_ROUTE_GLOBAL),
            // 로그인·가입·비밀번호
            new Route("POST", "^/v1/auth/(login|oauth/google|oauth/kakao)$", RateLimitRule.LOGIN_CLIENT),
            new Route("POST", "^/v1/auth/signup/(organization|parent|tutor)$", RateLimitRule.SIGNUP),
            new Route("POST", "^/v1/auth/signup/staff$", RateLimitRule.SIGNUP_STAFF),
            new Route("POST", "^/v1/auth/password-reset/request$", RateLimitRule.PASSWORD_RESET_REQUEST),
            new Route("POST", "^/v1/auth/password-reset/confirm$", RateLimitRule.PASSWORD_RESET_CONFIRM),
            new Route("POST", "^/v1/auth/me/(password|delete)$", RateLimitRule.PASSWORD_CHANGE),
            // 익명 쓰기
            new Route("POST", "^/v1/interactions$", RateLimitRule.INTERACTIONS),
            new Route("POST", "^/v1/session-recordings/chunks$", RateLimitRule.RECORDING_CHUNKS),
            new Route("POST", "^/v1/play-sessions/" + UUID + "/turns$", RateLimitRule.PLAY_SESSION_TURNS),
            new Route("POST", "^/v1/beta-events$", RateLimitRule.BETA_EVENTS),
            new Route("POST", "^/v1/client-errors$", RateLimitRule.CLIENT_ERRORS),
            new Route("POST", "^/v1/(recording-consents|me/recording-consent)$", RateLimitRule.RECORDING_CONSENTS),
            new Route("POST", "^/v1/launch-notifications$", RateLimitRule.LAUNCH_NOTIFICATIONS),
            new Route("POST", "^/v1/completion-surveys$", RateLimitRule.COMPLETION_SURVEYS),
            new Route("POST", "^/v1/voice-research(/withdraw)?$", RateLimitRule.VOICE_RESEARCH),
            new Route("POST", "^/v1/feedback$", RateLimitRule.FEEDBACK),
            new Route("GET", "^/v1/public-institutions$", RateLimitRule.PUBLIC_INSTITUTION_SEARCH),
            new Route("POST", "^/v1/organization-join-requests$", RateLimitRule.ORGANIZATION_JOIN_REQUEST));

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimitService rateLimitService, ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
    }

    static List<RateLimitRule> rulesFor(String method, String path) {
        for (Route route : ROUTES) {
            if (route.method().equals(method) && route.path().matcher(path).matches()) return route.rules();
        }
        return List.of();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (rateLimitService.enabled()) {
            for (RateLimitRule rule : rulesFor(request.getMethod(), request.getRequestURI())) {
                long retryAfter = rateLimitService.check(request, rule);
                if (retryAfter > 0) {
                    writeRateLimited(response, objectMapper, new RateLimitedException(rule, retryAfter));
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * 429 실패 봉투 - GlobalExceptionHandler의 RateLimitedException 응답과 같은 모양. retryable=false - 바로 다시 보내면 또
     * 걸리므로 클라이언트가 자동 재시도하지 않게 하고, 언제 다시 해 볼지는 Retry-After로 알린다.
     */
    static void writeRateLimited(HttpServletResponse response, ObjectMapper objectMapper, RateLimitedException error)
            throws IOException {
        response.setHeader("Retry-After", Long.toString(error.retryAfterSeconds()));
        HttpJsonWriter.writeJson(response, objectMapper, 429, FailureBody.of(
                new Failure(ErrorCode.RATE_LIMITED.name(), "upload", false, error.safeDetail())));
    }
}

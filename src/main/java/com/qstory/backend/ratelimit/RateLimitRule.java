package com.qstory.backend.ratelimit;

import java.time.Duration;

/**
 * 요청 상한 하나 - 창(window) 동안 requests번까지. 토큰 버킷이라 창 길이만큼 쉬면 다시 가득 찬다(한꺼번에 몰아 써도
 * requests번까지). 값은 application.yml의 qstory.rate-limit.limits.&lt;name&gt;으로 바꿀 수 있고, 여기 값은 그 설정이
 * 빠졌을 때의 기본값이다.
 *
 * <p>scope:
 * <ul>
 *   <li>PER_CLIENT - 로그인했으면 계정, 아니면 믿을 수 있는 클라이언트 주소마다 따로 센다.</li>
 *   <li>GLOBAL - 서버 전체가 함께 쓴다(주소를 바꿔 가며 보내도 돈이 드는 호출의 총량을 묶는다).</li>
 *   <li>GLOBAL_ANONYMOUS - 로그인하지 않은 호출만 서버 전체가 함께 센다(로그인한 UT 사용자 몫을 남긴다).</li>
 * </ul>
 */
public enum RateLimitRule {

    // ---- 돈·한도를 쓰는 익명 엔드포인트(보안 점검 M1)
    /** Gemini TTS(/v1/narrations, /stream). 미리 만들기(prefetch)까지 함께 센다. */
    TTS("tts", 40, Duration.ofMinutes(10), Scope.PER_CLIENT),
    /** 기본 Gemini 키(하루 100회, 데모 실시간 음성과 공유)를 쓰는 익명 TTS - 서버 전체 1시간 상한. 하루 상한은 따로(tts-anonymous-daily). */
    TTS_ANONYMOUS_HOURLY("tts-anonymous-hourly", 60, Duration.ofHours(1), Scope.GLOBAL_ANONYMOUS),
    COMPANION_CHAT("companion-chat", 30, Duration.ofMinutes(5), Scope.PER_CLIENT),
    COMPANION_CHAT_GLOBAL("companion-chat-global", 1200, Duration.ofHours(1), Scope.GLOBAL),
    STT("stt", 40, Duration.ofMinutes(5), Scope.PER_CLIENT),
    STT_GLOBAL("stt-global", 1500, Duration.ofHours(1), Scope.GLOBAL),
    QUESTION_ROUTE("question-route", 30, Duration.ofMinutes(5), Scope.PER_CLIENT),
    QUESTION_ROUTE_GLOBAL("question-route-global", 1200, Duration.ofHours(1), Scope.GLOBAL),

    // ---- 로그인·가입·비밀번호(보안 점검 M2)
    /** 로그인·소셜 로그인 - 주소마다. */
    LOGIN_CLIENT("login-client", 30, Duration.ofMinutes(15), Scope.PER_CLIENT),
    /** 같은 주소에서 같은 아이디로 - 비밀번호 대입을 막는다(AuthController). */
    LOGIN_ACCOUNT("login-account", 10, Duration.ofMinutes(15), Scope.PER_CLIENT),
    /** 아이디 하나에 주소를 바꿔 가며 - 넉넉하게 둔다(남이 일부러 막아 버리는 걸 줄이려고). */
    LOGIN_ID("login-id", 50, Duration.ofHours(1), Scope.GLOBAL),
    SIGNUP("signup", 10, Duration.ofHours(1), Scope.PER_CLIENT),
    SIGNUP_STAFF("signup-staff", 5, Duration.ofHours(1), Scope.PER_CLIENT),
    /** X-Admin-Token이 틀린 요청 - 맞힌 요청은 세지 않는다(AdminTokenGuard). */
    ADMIN_TOKEN_FAILURE("admin-token-failure", 10, Duration.ofHours(1), Scope.PER_CLIENT),
    PASSWORD_RESET_REQUEST("password-reset-request", 5, Duration.ofHours(1), Scope.PER_CLIENT),
    PASSWORD_RESET_CONFIRM("password-reset-confirm", 10, Duration.ofHours(1), Scope.PER_CLIENT),
    /** 로그인한 채 비밀번호 바꾸기·탈퇴 - 지금 비밀번호를 확인하므로 토큰을 훔친 쪽의 대입을 막는다. */
    PASSWORD_CHANGE("password-change", 10, Duration.ofMinutes(15), Scope.PER_CLIENT),

    // ---- 익명 쓰기(넉넉하게 - 실수로 쏟아지는 양과 악의적인 채우기만 막는다)
    INTERACTIONS("interactions", 120, Duration.ofMinutes(1), Scope.PER_CLIENT),
    RECORDING_CHUNKS("recording-chunks", 30, Duration.ofMinutes(1), Scope.PER_CLIENT),
    PLAY_SESSION_TURNS("play-session-turns", 60, Duration.ofMinutes(1), Scope.PER_CLIENT),
    BETA_EVENTS("beta-events", 120, Duration.ofMinutes(1), Scope.PER_CLIENT),
    CLIENT_ERRORS("client-errors", 120, Duration.ofMinutes(1), Scope.PER_CLIENT),
    RECORDING_CONSENTS("recording-consents", 30, Duration.ofHours(1), Scope.PER_CLIENT),
    LAUNCH_NOTIFICATIONS("launch-notifications", 10, Duration.ofHours(1), Scope.PER_CLIENT),
    COMPLETION_SURVEYS("completion-surveys", 10, Duration.ofHours(1), Scope.PER_CLIENT),
    VOICE_RESEARCH("voice-research", 30, Duration.ofHours(1), Scope.PER_CLIENT),
    FEEDBACK("feedback", 20, Duration.ofHours(1), Scope.PER_CLIENT),

    // ---- 공공 기관 목록 검색과 소속 신청(083)
    /** 기관 검색 - 글자를 칠 때마다(앱은 쉬었다가) 불린다. 익명(원장 가입 화면)도 부른다. */
    PUBLIC_INSTITUTION_SEARCH("public-institution-search", 60, Duration.ofMinutes(1), Scope.PER_CLIENT),
    /** 선생님 소속 신청 - 원장에게 알림이 가므로 마구 보내지 못하게. */
    ORGANIZATION_JOIN_REQUEST("organization-join-request", 20, Duration.ofHours(1), Scope.PER_CLIENT);

    public enum Scope { PER_CLIENT, GLOBAL, GLOBAL_ANONYMOUS }

    private final String id;
    private final int defaultRequests;
    private final Duration defaultWindow;
    private final Scope scope;

    RateLimitRule(String id, int defaultRequests, Duration defaultWindow, Scope scope) {
        this.id = id;
        this.defaultRequests = defaultRequests;
        this.defaultWindow = defaultWindow;
        this.scope = scope;
    }

    /** 로그·설정 키에 쓰는 이름(rate-limit.blocked group=...). */
    public String id() {
        return id;
    }

    public int defaultRequests() {
        return defaultRequests;
    }

    public Duration defaultWindow() {
        return defaultWindow;
    }

    public Scope scope() {
        return scope;
    }
}

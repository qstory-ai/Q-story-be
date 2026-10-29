package com.qstory.backend.discord.service;

import com.qstory.backend.config.DiscordProperties;
import com.qstory.backend.discord.repository.DiscordStateRepository;
import com.qstory.backend.provider.gemini.util.GeminiTtsClient;
import com.qstory.backend.provider.gemini.util.GeminiTtsUsageListener;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.OptionalDouble;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 한도·비용 경고(#소식). 같은 경고는 하루 한 번만(discord_alert_log에 (키, 날짜)를 먼저 남긴 쪽만 보낸다).
 * <ul>
 *   <li>Gemini TTS 기본 키 하루 호출 수가 경고 단계(기본 80·95회)를 넘을 때 - @개발. 날짜는 Gemini 한도와 같은 미국 태평양 기준.
 *   <li>Gemini TTS가 429(한도 소진)를 돌려줄 때 - @개발.
 *   <li>OpenRouter 잔액이 기준 아래일 때 - @개발 @운영(비용).
 * </ul>
 * TTS 호출 수는 provider_daily_usage에 센다(재배포해도 이어진다). 세기는 DiscordJobRunner 스레드에서 한다.
 */
@Service
public class LimitAlertService implements GeminiTtsUsageListener {

    static final ZoneId GEMINI_RESET_ZONE = ZoneId.of("America/Los_Angeles");
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final String GEMINI_PROVIDER = "gemini-tts";

    private final DiscordProperties properties;
    private final DiscordNotifier notifier;
    private final DiscordStateRepository state;
    private final DiscordJobRunner jobs;
    private final OpenRouterBalanceClient openRouter;
    private final Clock clock;

    @Autowired
    public LimitAlertService(
            DiscordProperties properties, DiscordNotifier notifier, DiscordStateRepository state, DiscordJobRunner jobs,
            OpenRouterBalanceClient openRouter, GeminiTtsClient primaryTts) {
        this(properties, notifier, state, jobs, openRouter, Clock.systemUTC());
        // 주입되는 것은 @Primary(기본 키) 인스턴스 - 미리 만들기 전용 키 인스턴스는 세지 않는다.
        primaryTts.setUsageListener(this);
    }

    LimitAlertService(
            DiscordProperties properties, DiscordNotifier notifier, DiscordStateRepository state, DiscordJobRunner jobs,
            OpenRouterBalanceClient openRouter, Clock clock) {
        this.properties = properties;
        this.notifier = notifier;
        this.state = state;
        this.jobs = jobs;
        this.openRouter = openRouter;
        this.clock = clock;
    }

    @Override
    public void onCall() {
        if (properties.limitAlertsOn()) {
            jobs.submit("gemini-tts-count", this::countGeminiCall);
        }
    }

    @Override
    public void onRateLimited() {
        if (properties.limitAlertsOn()) {
            jobs.submit("gemini-tts-429", this::alertGeminiRateLimited);
        }
    }

    void countGeminiCall() {
        LocalDate day = LocalDate.now(clock.withZone(GEMINI_RESET_ZONE));
        int used = state.incrementUsage(GEMINI_PROVIDER, day);
        int limit = properties.geminiTtsDailyLimitOrDefault();
        Integer crossed = null;
        for (int step : properties.geminiTtsWarnSteps()) {
            if (used >= step) {
                crossed = step;
            }
        }
        if (crossed == null) {
            return;
        }
        // 가장 높이 넘은 단계 하나만 보낸다(재배포 등으로 한꺼번에 넘었을 때 두 줄이 되지 않게). 낮은 단계도 표시만 해 둔다.
        boolean fresh = state.markOnce("gemini-tts-" + crossed, day);
        for (int step : properties.geminiTtsWarnSteps()) {
            if (step < crossed) {
                state.markOnce("gemini-tts-" + step, day);
            }
        }
        if (fresh) {
            send("gemini-tts-" + crossed, day, DiscordMessages.geminiTtsUsage(used, limit, crossed),
                    List.of(DiscordMention.DEV));
        }
    }

    void alertGeminiRateLimited() {
        LocalDate day = LocalDate.now(clock.withZone(GEMINI_RESET_ZONE));
        if (state.markOnce("gemini-tts-429", day)) {
            send("gemini-tts-429", day, "**[한도]** Gemini TTS가 429(한도 초과)를 돌려줬어요. 미국 태평양 자정까지 실시간 음성이 "
                    + "막힐 수 있어요.", List.of(DiscordMention.DEV));
        }
    }

    /** 정기 확인(1시간마다). 잔액을 못 읽으면(키 없음·API 실패) 조용히 넘어간다. */
    public void checkOpenRouterBalance() {
        double threshold = properties.openrouterLowBalanceUsdOrDefault();
        if (!properties.limitAlertsOn() || threshold <= 0) {
            return;
        }
        OptionalDouble balance = openRouter.remainingUsd();
        if (balance.isEmpty() || balance.getAsDouble() >= threshold) {
            return;
        }
        LocalDate day = LocalDate.now(clock.withZone(KST));
        if (state.markOnce("openrouter-low-balance", day)) {
            send("openrouter-low-balance", day, DiscordMessages.openRouterLowBalance(balance.getAsDouble(), threshold),
                    List.of(DiscordMention.DEV, DiscordMention.OPS));
        }
    }

    private void send(String key, LocalDate day, String text, List<DiscordMention> mentions) {
        if (!notifier.send(DiscordChannel.ALERTS, text, mentions)) {
            state.unmark(key, day);
        }
    }
}

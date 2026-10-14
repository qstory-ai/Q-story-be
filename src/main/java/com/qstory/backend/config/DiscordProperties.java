package com.qstory.backend.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 디스코드 알림 설정(qstory.discord). 웹훅 주소는 비밀값이라 로그에 찍지 않는다. 채널 웹훅이 비어 있으면 그 채널 알림은
 * 조용히 건너뛰고, 역할 ID가 비어 있으면 멘션 없이 보낸다. enabled=false(QSTORY_DISCORD_ENABLED)면 전부 끈다.
 */
@ConfigurationProperties(prefix = "qstory.discord")
public record DiscordProperties(
        Boolean enabled,
        String webhookMetrics,
        String webhookDeploy,
        String webhookNews,
        String roleOpsId,
        String roleDevId,
        Boolean dailyMetricsEnabled,
        Boolean newsDigestEnabled,
        Boolean limitAlertsEnabled,
        String metricsLink,
        String newsLink,
        Integer geminiTtsDailyLimit,
        String geminiTtsWarnAt,
        Double openrouterLowBalanceUsd) {

    public boolean on() {
        return !Boolean.FALSE.equals(enabled);
    }

    public boolean dailyMetricsOn() {
        return on() && !Boolean.FALSE.equals(dailyMetricsEnabled) && notBlank(webhookMetrics);
    }

    public boolean newsDigestOn() {
        return on() && !Boolean.FALSE.equals(newsDigestEnabled) && notBlank(webhookNews);
    }

    public boolean limitAlertsOn() {
        return on() && !Boolean.FALSE.equals(limitAlertsEnabled) && notBlank(webhookNews);
    }

    public int geminiTtsDailyLimitOrDefault() {
        return geminiTtsDailyLimit == null || geminiTtsDailyLimit < 1 ? 100 : geminiTtsDailyLimit;
    }

    /** "80,95" → [80, 95] (오름차순, 숫자가 아닌 칸은 버린다). */
    public List<Integer> geminiTtsWarnSteps() {
        List<Integer> steps = new ArrayList<>();
        String raw = geminiTtsWarnAt == null || geminiTtsWarnAt.isBlank() ? "80,95" : geminiTtsWarnAt;
        for (String part : raw.split(",")) {
            try {
                int value = Integer.parseInt(part.trim());
                if (value > 0) steps.add(value);
            } catch (NumberFormatException ignored) {
                // 잘못 적은 칸은 건너뛴다.
            }
        }
        steps.sort(Integer::compareTo);
        return steps;
    }

    public double openrouterLowBalanceUsdOrDefault() {
        return openrouterLowBalanceUsd == null ? 5.0 : openrouterLowBalanceUsd;
    }

    public static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}

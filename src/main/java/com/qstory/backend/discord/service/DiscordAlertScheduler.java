package com.qstory.backend.discord.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 디스코드 정기 알림 시각(한국 시간). 실제 집계·전송은 DiscordJobRunner 스레드로 넘긴다.
 * <ul>
 *   <li>09:00 아침 지표 요약(#지표) - GA 가져오기(04:40·07:40) 뒤.
 *   <li>매시 01분·31분 사용자 반응 30분 묶음(#소식) - 창 끝(00·30분) 직후, 늦게 커밋된 행까지 들어오게 1분 뒤.
 *   <li>매시 07분 OpenRouter 잔액 확인(#소식).
 * </ul>
 */
@Component
public class DiscordAlertScheduler {

    private final DiscordReportService reports;
    private final LimitAlertService limits;
    private final DiscordJobRunner jobs;

    public DiscordAlertScheduler(DiscordReportService reports, LimitAlertService limits, DiscordJobRunner jobs) {
        this.reports = reports;
        this.limits = limits;
        this.jobs = jobs;
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void dailyMetrics() {
        jobs.submit("daily-metrics", reports::scheduledDailyMetrics);
    }

    @Scheduled(cron = "0 1,31 * * * *", zone = "Asia/Seoul")
    public void newsDigest() {
        jobs.submit("news-digest", reports::runNewsDigest);
    }

    @Scheduled(cron = "0 7 * * * *", zone = "Asia/Seoul")
    public void openRouterBalance() {
        jobs.submit("openrouter-balance", limits::checkOpenRouterBalance);
    }
}

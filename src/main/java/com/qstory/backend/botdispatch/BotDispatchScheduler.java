package com.qstory.backend.botdispatch;

import com.qstory.backend.config.BotDispatchProperties;
import com.qstory.backend.config.BotDispatchProperties.Job;
import com.qstory.backend.discord.repository.DiscordStateRepository;
import com.qstory.backend.discord.service.DiscordChannel;
import com.qstory.backend.discord.service.DiscordMention;
import com.qstory.backend.discord.service.DiscordNotifier;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * 팀 봇 워크플로를 정시에 실행 요청한다(qstory.bot-dispatch.jobs, 한국 시간 cron). @Scheduled와 같은 공용 scheduling 풀에서 돈다.
 * CronTrigger는 지금 시각 다음 회차부터 잡으므로 재시작 동안 놓친 회차는 몰아서 보내지 않고 건너뛴다.
 * 실패(4xx·5xx·연결 실패)는 로그 bot-dispatch.failed + 같은 워크플로는 하루(한국 날짜) 한 번만 #소식 @개발 알림.
 */
@Component
public class BotDispatchScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(BotDispatchScheduler.class);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final String ALERT_KEY_PREFIX = "bot-dispatch-";

    private final BotDispatchProperties properties;
    private final GitHubDispatchClient client;
    private final DiscordNotifier notifier;
    private final DiscordStateRepository state;
    private final Clock clock;

    /** 생성자가 둘이라 Spring이 쓸 생성자를 명시한다 - 없으면 기본 생성자를 찾다 부팅이 실패한다. */
    @Autowired
    public BotDispatchScheduler(
            BotDispatchProperties properties, GitHubDispatchClient client, DiscordNotifier notifier,
            DiscordStateRepository state) {
        this(properties, client, notifier, state, Clock.systemUTC());
    }

    BotDispatchScheduler(
            BotDispatchProperties properties, GitHubDispatchClient client, DiscordNotifier notifier,
            DiscordStateRepository state, Clock clock) {
        this.properties = properties;
        this.client = client;
        this.notifier = notifier;
        this.state = state;
        this.clock = clock;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        for (CronTask task : tasks()) {
            registrar.addCronTask(task);
        }
    }

    /** 켜져 있을 때 등록할 작업. 토큰이 없거나 꺼져 있으면 빈 목록(조용히 비활성). */
    List<CronTask> tasks() {
        if (!properties.on()) {
            log.info("bot-dispatch.disabled");
            return List.of();
        }
        List<CronTask> tasks = properties.activeJobs().stream()
                .map(job -> new CronTask(() -> run(job), trigger(job)))
                .toList();
        log.info("bot-dispatch.scheduled jobs={}", tasks.size());
        return tasks;
    }

    static CronTrigger trigger(Job job) {
        return new CronTrigger(job.cron().trim(), KST);
    }

    void run(Job job) {
        long startedAt = System.nanoTime();
        GitHubDispatchClient.Result result =
                client.dispatch(properties.token(), properties.repo(), job.workflow(), properties.refOrDefault());
        long ms = (System.nanoTime() - startedAt) / 1_000_000;
        if (result.ok()) {
            log.info("bot-dispatch.ok workflow={} status={} ms={}", job.workflow(), result.status(), ms);
            return;
        }
        String reason = result.error() != null ? "reason=" + result.error() : "status=" + result.status();
        log.warn("bot-dispatch.failed workflow={} {} ms={}", job.workflow(), reason, ms);
        alertOnce(job.workflow(), reason);
    }

    void alertOnce(String workflow, String reason) {
        String key = ALERT_KEY_PREFIX + workflow;
        LocalDate day = LocalDate.now(clock.withZone(KST));
        try {
            if (!notifier.configured(DiscordChannel.NEWS) || !state.markOnce(key, day)) {
                return;
            }
            String text = "**[봇]** `" + workflow + "` 정시 실행 요청이 실패했어요 (" + reason + "). "
                    + "예비 schedule로 늦게 돌 수 있어요. 이 워크플로 알림은 오늘 다시 보내지 않아요.";
            if (!notifier.send(DiscordChannel.NEWS, text, List.of(DiscordMention.DEV))) {
                state.unmark(key, day);
            }
        } catch (RuntimeException failure) {
            log.warn("bot-dispatch.alert-failed workflow={} reason={}", workflow, failure.getClass().getSimpleName());
        }
    }
}

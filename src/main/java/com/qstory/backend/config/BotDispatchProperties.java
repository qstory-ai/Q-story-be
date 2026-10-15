package com.qstory.backend.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 팀 봇 워크플로 정시 실행 요청 설정(qstory.bot-dispatch). GitHub Actions의 schedule은 몇 시간씩 밀릴 수 있어
 * 백엔드가 정시에 workflow_dispatch를 부른다. 토큰은 비밀값이라 로그에 찍지 않는다. 토큰이 비어 있거나
 * enabled=false(QSTORY_BOT_DISPATCH_ENABLED)면 아무것도 하지 않는다.
 *
 * @param jobs 워크플로 파일 이름과 Spring cron(6칸, 한국 시간). cron이 "-"이거나 비어 있으면 그 작업은 끈다.
 */
@ConfigurationProperties(prefix = "qstory.bot-dispatch")
public record BotDispatchProperties(Boolean enabled, String token, String repo, String ref, List<Job> jobs) {

    public record Job(String workflow, String cron) {

        public boolean on() {
            return DiscordProperties.notBlank(workflow) && DiscordProperties.notBlank(cron) && !"-".equals(cron.trim());
        }
    }

    public boolean on() {
        return !Boolean.FALSE.equals(enabled) && DiscordProperties.notBlank(token) && DiscordProperties.notBlank(repo);
    }

    public String refOrDefault() {
        return DiscordProperties.notBlank(ref) ? ref.trim() : "main";
    }

    public List<Job> activeJobs() {
        return jobs == null ? List.of() : jobs.stream().filter(job -> job != null && job.on()).toList();
    }
}

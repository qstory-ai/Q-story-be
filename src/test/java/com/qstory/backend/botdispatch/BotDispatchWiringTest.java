package com.qstory.backend.botdispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.qstory.backend.config.BotDispatchProperties;
import com.qstory.backend.discord.repository.DiscordStateRepository;
import com.qstory.backend.discord.service.DiscordNotifier;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** 운영 부팅 실패(생성자 둘 - 기본 생성자 탐색) 재발 방지: Spring이 이 빈들을 실제로 만들 수 있는지 컨텍스트로 확인한다. */
class BotDispatchWiringTest {

    @Configuration
    @EnableConfigurationProperties(BotDispatchProperties.class)
    static class PropertiesConfig {}

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(ctx -> {
                    try {
                        for (PropertySource<?> source :
                                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
                            ctx.getEnvironment().getPropertySources().addLast(source);
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withUserConfiguration(PropertiesConfig.class)
                .withBean(DiscordNotifier.class, () -> mock(DiscordNotifier.class))
                .withBean(DiscordStateRepository.class, () -> mock(DiscordStateRepository.class))
                .withBean(GitHubDispatchClient.class)
                .withBean(BotDispatchScheduler.class);
    }

    @Test
    void springCanConstructBeansWithoutToken() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(GitHubDispatchClient.class);
            assertThat(ctx.getBean(BotDispatchScheduler.class).tasks()).isEmpty();
        });
    }

    @Test
    void applicationYmlDefaultsRegisterFourValidCronJobs() {
        runner().withPropertyValues("GITHUB_DISPATCH_TOKEN=test-token").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            BotDispatchProperties properties = ctx.getBean(BotDispatchProperties.class);
            assertThat(properties.activeJobs()).extracting(BotDispatchProperties.Job::workflow).containsExactly(
                    "linear-notion-sync.yml", "daily-scrum.yml", "auto-dev-scrum.yml", "scrum-remind.yml");
            assertThat(properties.refOrDefault()).isEqualTo("main");
            assertThat(ctx.getBean(BotDispatchScheduler.class).tasks()).hasSize(4);
        });
    }

    @Test
    void killSwitchRegistersNothing() {
        runner().withPropertyValues("GITHUB_DISPATCH_TOKEN=test-token", "QSTORY_BOT_DISPATCH_ENABLED=false").run(ctx ->
                assertThat(ctx.getBean(BotDispatchScheduler.class).tasks()).isEmpty());
    }
}

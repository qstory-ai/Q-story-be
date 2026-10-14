package com.qstory.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 운영 부팅 실패(생성자 둘 - 기본 생성자 탐색) 재발 방지: 설정값이 실제로 바인딩되는지 컨텍스트로 확인한다. */
class DialoguePropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties(DialogueProperties.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void bindsValuesFromProperties() {
        runner.withPropertyValues("qstory.dialogue.try-and-fail-enabled=false",
                        "qstory.dialogue.max-attempts-per-anchor=3", "qstory.dialogue.max-attempt-turns=5")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    DialogueProperties p = ctx.getBean(DialogueProperties.class);
                    assertThat(p.tryAndFail()).isFalse();
                    assertThat(p.maxAttemptsPerAnchor()).isEqualTo(3);
                    assertThat(p.maxAttemptTurns()).isEqualTo(5);
                });
    }

    @Test
    void defaultsWhenUnset() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            DialogueProperties p = ctx.getBean(DialogueProperties.class);
            assertThat(p.tryAndFail()).isTrue();
            assertThat(p.maxAttemptsPerAnchor()).isEqualTo(1);
            assertThat(p.maxAttemptTurns()).isEqualTo(4);
        });
    }
}

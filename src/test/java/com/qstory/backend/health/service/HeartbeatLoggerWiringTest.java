package com.qstory.backend.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/** 운영 부팅 실패(생성자 둘 - 기본 생성자 탐색) 재발 방지: Spring이 이 빈을 실제로 만들 수 있는지 컨텍스트로 확인한다. */
class HeartbeatLoggerWiringTest {

    @Test
    void springCanConstructHeartbeatLogger() {
        new ApplicationContextRunner()
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(HeartbeatLogger.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(HeartbeatLogger.class);
                });
    }
}

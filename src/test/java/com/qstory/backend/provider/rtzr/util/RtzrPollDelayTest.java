package com.qstory.backend.provider.rtzr.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 짧은 말은 1~2초 안에 끝난다 - 처음 몇 번은 빨리 확인하고, 그다음 1.5초 간격으로 돌아간다. */
class RtzrPollDelayTest {

    @Test
    void firstChecksComeQuicklyThenSettleAtOneAndAHalfSeconds() {
        assertThat(RtzrSttClient.pollDelay(0)).isEqualTo(Duration.ofMillis(500));
        assertThat(RtzrSttClient.pollDelay(1)).isEqualTo(Duration.ofMillis(500));
        assertThat(RtzrSttClient.pollDelay(3)).isEqualTo(Duration.ofMillis(1_000));
        assertThat(RtzrSttClient.pollDelay(4)).isEqualTo(Duration.ofMillis(1_500));
        assertThat(RtzrSttClient.pollDelay(20)).isEqualTo(Duration.ofMillis(1_500));
    }
}

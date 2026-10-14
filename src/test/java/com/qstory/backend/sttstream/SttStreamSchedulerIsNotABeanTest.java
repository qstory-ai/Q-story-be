package com.qstory.backend.sttstream;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;

/**
 * ScheduledExecutorService·TaskScheduler 빈이 하나라도 생기면 Spring이 그것을 모든 @Scheduled 작업의 실행기로 쓰고
 * spring.task.scheduling(풀 4) 자동 설정이 물러난다 - 받아 적기 타이머 풀(2개)이 리포트 분석 LLM 호출까지 떠안던 문제.
 */
class SttStreamSchedulerIsNotABeanTest {

    @Test
    void streamTimerPoolIsNotExposedAsASchedulerBean() {
        boolean exposesScheduler = Arrays.stream(SttStreamWebSocketConfig.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .map(Method::getReturnType)
                .anyMatch(type -> ScheduledExecutorService.class.isAssignableFrom(type)
                        || TaskScheduler.class.isAssignableFrom(type));
        assertFalse(exposesScheduler);
    }
}

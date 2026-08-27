package com.qstory.backend.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 이 코드베이스 최초의 비동기 인프라(@Async/ExecutorService). LiveBranchExecutionWorker가 LLM/이미지
 * 생성을 아이 응답 경로 밖에서 백그라운드로 처리할 수 있게 해준다 - 나머지 파이프라인은 여전히 전부
 * 동기식이라 이 실행기는 오직 그 한 용도로만 쓰인다.
 *
 * <p>Phase 2부터 LiveBranchExecutionWorker.run() 하나가 같은 풀에 최대 3개의 서브 작업(family당
 * 하나)을 추가로 제출한다 - 즉 job 하나가 동시에 최대 4개 스레드(오케스트레이팅 1 + 서브 작업 최대 3)를
 * 쓸 수 있다. core=2/max=4였던 Phase 1 크기로는 job이 2개만 겹쳐도 큐잉이 발생하므로 core=4/max=8로
 * 올렸다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    public Executor liveBranchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("live-branch-");
        // 배포/셧다운 중간에 생성이 끊겨 "유령" 상태로 남는 job을 줄인다 - 그래도 완전히 막지는
        // 못하므로 LiveBranchStaleJobReaper가 마지막 안전망으로 남는다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}

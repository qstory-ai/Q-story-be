package com.qstory.backend.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * LiveBranchExecutionWorker 전용 비동기 실행기 - LLM/이미지 생성을 아이 응답 경로 밖에서 처리한다.
 *
 * <p>run()은 family당 서브 작업을 최대 3개 제출하고 {@code CompletableFuture.join()}으로 기다린다.
 * 두 작업을 같은 풀에서 돌리면 데드락이 난다: 동시 job 수가 corePoolSize에 닿으면 core 스레드 전부가
 * join()에 블록되고, ThreadPoolExecutor는 큐가 가득 차야만 스레드를 늘리므로 큐에 쌓인 서브 작업을
 * 실행할 스레드가 남지 않는다. 그래서 오케스트레이션(liveBranchExecutor)과 서브 작업
 * (liveBranchSubtaskExecutor)을 서로 다른 풀로 둔다.
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

    /** LiveBranchExecutionWorker.run()이 join()으로 기다리는 family별 서브 작업 전용 풀 - job 하나당 최대 3개. */
    @Bean
    public Executor liveBranchSubtaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(40);
        executor.setThreadNamePrefix("live-branch-sub-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}

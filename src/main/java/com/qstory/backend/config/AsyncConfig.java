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
 * <p>Phase 2부터 LiveBranchExecutionWorker.run()이 family당 서브 작업을 최대 3개 추가로 제출하고
 * {@code CompletableFuture.join()}으로 그 완료를 기다린다. 오케스트레이션과 서브 작업을 같은 풀에서
 * 돌리면(과거에 그랬다) 진짜 데드락이 난다: 동시 진행 중인 job 수가 풀의 corePoolSize에 도달하면
 * core 스레드 전부가 join()에 블록되고, 그 서브 작업들은 큐에 쌓인 채 실행할 스레드가 하나도 남지
 * 않는다 - ThreadPoolExecutor는 큐가 가득 차야만 max까지 스레드를 늘리지, "core가 전부 바쁨"만으로는
 * 늘리지 않기 때문이다(큐 용량이 넉넉하면 절대 가득 차지 않아 영원히 늘지 않는다). 그래서 오케스트레이팅
 * 스레드(liveBranchExecutor)와 그것이 join()으로 기다리는 서브 작업(liveBranchSubtaskExecutor)을
 * 서로 다른 풀로 분리했다 - 이제 한쪽이 전부 블록돼도 다른 쪽 풀의 스레드는 그대로 남아 서브 작업을
 * 처리할 수 있다.
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

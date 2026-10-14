package com.qstory.backend.discord.service;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 디스코드 알림용 집계·확인 작업을 돌리는 전용 스레드 하나. @Scheduled 스레드는 앱 전체가 하나를 같이 쓰므로
 * (app.heartbeat 포함) DB 집계나 OpenRouter 잔액 조회를 기다리느라 다른 정기 작업이 밀리지 않게 넘기기만 한다.
 * TTS 호출 수 세기도 여기서 해서 음성 응답 경로가 DB 쓰기를 기다리지 않게 한다. 실패는 로그 한 줄.
 */
@Component
public class DiscordJobRunner {

    private static final Logger log = LoggerFactory.getLogger(DiscordJobRunner.class);

    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(500), runnable -> {
                Thread thread = new Thread(runnable, "discord-jobs");
                thread.setDaemon(true);
                return thread;
            });

    public void submit(String name, Runnable job) {
        try {
            worker.execute(() -> {
                try {
                    job.run();
                } catch (RuntimeException failure) {
                    log.warn("discord.job-failed job={} reason={}", name, failure.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException full) {
            log.warn("discord.job-failed job={} reason=queue-full", name);
        }
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }
}

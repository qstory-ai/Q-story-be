package com.qstory.backend.discord.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.config.DiscordProperties;
import jakarta.annotation.PreDestroy;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 디스코드 채널로 한 줄 보내기. send()는 전용 스레드 하나에 넘기기만 하고 바로 돌아온다 - 호출한 요청·정기 작업은
 * 디스코드 응답을 기다리지 않고, 실패해도 로그 한 줄(discord.failed)뿐이다. 대기열(200)이 차면 버린다.
 * 킬스위치(qstory.discord.enabled=false)나 웹훅이 비어 있는 채널이면 아무것도 하지 않는다.
 */
@Component
public class DiscordNotifier {

    private static final Logger log = LoggerFactory.getLogger(DiscordNotifier.class);

    private final DiscordProperties properties;
    private final ObjectMapper objectMapper;
    private final DiscordWebhookClient client;
    private final ThreadPoolExecutor worker;

    @Autowired
    public DiscordNotifier(DiscordProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new DiscordWebhookClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), objectMapper, Thread::sleep));
    }

    DiscordNotifier(DiscordProperties properties, ObjectMapper objectMapper, DiscordWebhookClient client) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.client = client;
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(200), runnable -> {
            Thread thread = new Thread(runnable, "discord-webhook");
            thread.setDaemon(true);
            return thread;
        });
    }

    public boolean configured(DiscordChannel channel) {
        return properties.on() && DiscordProperties.notBlank(webhook(channel));
    }

    /** 비동기로 보낸다. 넘겼으면 true(보내기 성공 여부는 로그로만). */
    public boolean send(DiscordChannel channel, String text, Collection<DiscordMention> mentions) {
        if (!configured(channel)) {
            return false;
        }
        try {
            worker.execute(() -> deliver(channel, text, mentions));
            return true;
        } catch (RejectedExecutionException full) {
            log.warn("discord.failed tag={} reason=queue-full", tag(channel));
            return false;
        }
    }

    /** 이 스레드에서 보내고 결과를 돌려준다 - 관리자 수동 실행용. */
    public boolean sendNow(DiscordChannel channel, String text, Collection<DiscordMention> mentions) {
        if (!configured(channel)) {
            return false;
        }
        return deliver(channel, text, mentions);
    }

    private boolean deliver(DiscordChannel channel, String text, Collection<DiscordMention> mentions) {
        try {
            List<String> roles = DiscordPayload.roleIds(properties, mentions);
            return client.post(webhook(channel), DiscordPayload.build(objectMapper, text, roles), tag(channel));
        } catch (RuntimeException unexpected) {
            log.warn("discord.failed tag={} reason={}", tag(channel), unexpected.getClass().getSimpleName());
            return false;
        }
    }

    private String webhook(DiscordChannel channel) {
        return switch (channel) {
            case METRICS -> properties.webhookMetrics();
            case DEPLOY -> properties.webhookDeploy();
            case NEWS -> properties.webhookNews();
            case ALERTS -> properties.alertsWebhook();
        };
    }

    private static String tag(DiscordChannel channel) {
        return channel.name().toLowerCase();
    }

    @PreDestroy
    void shutdown() {
        worker.shutdown();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.qstory.backend.sttstream;

import com.qstory.backend.config.AppProperties;
import com.qstory.backend.conversationrecord.service.ConversationRecordService;
import com.qstory.backend.provider.rtzr.util.RtzrStreamingClient;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * 실시간 받아 적기 WebSocket(/v1/stt/stream). 웹은 Vercel 프록시가 WebSocket을 들고 있지 못해 백엔드에 직접 붙는다 -
 * 그래서 프록시 뒤라 CORS가 필요 없던 웹 출처도 여기서는 허용해야 한다.
 */
@Configuration
@EnableWebSocket
public class SttStreamWebSocketConfig implements WebSocketConfigurer, DisposableBean {

    /**
     * 웹 배포 출처 - REST는 같은 출처 프록시를 거쳐 ALLOWED_ORIGINS(배포 환경변수)에 웹 출처가 없어도 되지만, 이 소켓은
     * 브라우저가 직접 연다. 환경변수가 목록을 통째로 바꿔도 소켓이 막히지 않게 코드에 둔다(표가 실제 인증이고 이건 덧댐).
     */
    static final List<String> WEB_ORIGINS = List.of(
            "https://demo.qstory.ai.kr",
            "https://dev.qstory.ai.kr",
            "https://play.qstory.ai.kr",
            "https://qstory.ai.kr",
            "https://www.qstory.ai.kr",
            "https://start.qstory.ai.kr");

    public static final String STREAM_PATH = "/v1/stt/stream";

    private final AppProperties config;
    private final SttStreamTicketService ticketService;
    private final RtzrStreamingClient rtzr;
    private final ConversationRecordService conversationRecordService;

    public SttStreamWebSocketConfig(
            AppProperties config, SttStreamTicketService ticketService, RtzrStreamingClient rtzr,
            ConversationRecordService conversationRecordService) {
        this.config = config;
        this.ticketService = ticketService;
        this.rtzr = rtzr;
        this.conversationRecordService = conversationRecordService;
    }

    /**
     * 환경별 allowed-origins + 앱 WebView 출처(native-origins) + 웹 배포 출처. 업그레이드 요청도 Spring Security의 CORS
     * 필터를 거치므로 SecurityConfig가 이 경로에 같은 목록을 건다(안 그러면 Origin이 낯설다고 403).
     */
    public static List<String> streamOrigins(AppProperties config) {
        return Stream.of(
                        Optional.ofNullable(config.allowedOrigins()).orElseGet(List::of),
                        Optional.ofNullable(config.nativeOrigins()).orElseGet(List::of),
                        WEB_ORIGINS)
                .flatMap(List::stream)
                .distinct()
                .toList();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        List<String> origins = streamOrigins(config);
        registry.addHandler(
                        new SttStreamWebSocketHandler(ticketService, rtzr, conversationRecordService, sttStreamScheduler),
                        STREAM_PATH)
                .addInterceptors(new SttStreamHandshakeInterceptor(ticketService, origins))
                .setAllowedOrigins(origins.toArray(String[]::new));
    }

    /**
     * 끝 요청 대기·최대 길이·닫기 유예 타이머와 대화 원장 쓰기. 빈으로 두지 않는다 - ScheduledExecutorService 빈이
     * 하나 있으면 Spring이 그것을 모든 @Scheduled 작업의 실행기로 쓰고(스케줄러 자동 설정은 물러난다), 리포트 분석의
     * LLM 호출·보존 정리가 이 두 스레드에서 돌며 받아 적기 타이머를 밀어냈다. @Scheduled는 spring.task.scheduling 풀.
     */
    private final ScheduledExecutorService sttStreamScheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "stt-stream");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void destroy() {
        sttStreamScheduler.shutdownNow();
    }

    /** PCM 조각(100ms = 3.2KB)이 Tomcat 기본 버퍼(8KB)를 넘지 않지만 여유를 둔다. 60초 동안 아무것도 없으면 닫는다. */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(64 * 1024);
        container.setMaxTextMessageBufferSize(8 * 1024);
        container.setMaxSessionIdleTimeout(60_000L);
        return container;
    }
}

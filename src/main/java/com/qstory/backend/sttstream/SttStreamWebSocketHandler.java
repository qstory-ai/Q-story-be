package com.qstory.backend.sttstream;

import com.qstory.backend.conversationrecord.service.ConversationRecordService;
import com.qstory.backend.provider.rtzr.util.RtzrStreamingClient;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * wss /v1/stt/stream?ticket=... - 표 확인은 연결 전에 SttStreamHandshakeInterceptor가 끝냈다.
 * 앱 → 서버: binary = 16kHz mono 16bit little-endian PCM, text {"type":"end"} = 말이 끝남.
 * 서버 → 앱: {"type":"ready"}, {"type":"partial","text"}, {"type":"final","ok":true,"transcript"} 또는
 * {"type":"final","ok":false,"code"}, {"type":"error","code"} - final/error 뒤에는 서버가 닫는다.
 */
public class SttStreamWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SttStreamWebSocketHandler.class);
    static final String SESSION_ATTRIBUTE = "sttStreamSession";
    /** 앱 쪽 보내기 버퍼 상한 - 중간 문장은 작으니 넉넉하다. 넘으면 그 연결을 닫는다. */
    private static final int SEND_BUFFER_LIMIT_BYTES = 256 * 1024;
    private static final int SEND_TIME_LIMIT_MS = 5_000;

    private final SttStreamTicketService ticketService;
    private final RtzrStreamingClient rtzr;
    private final ConversationRecordService conversationRecordService;
    private final ScheduledExecutorService scheduler;

    public SttStreamWebSocketHandler(
            SttStreamTicketService ticketService, RtzrStreamingClient rtzr,
            ConversationRecordService conversationRecordService, ScheduledExecutorService scheduler) {
        this.ticketService = ticketService;
        this.rtzr = rtzr;
        this.conversationRecordService = conversationRecordService;
        this.scheduler = scheduler;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) throws Exception {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(
                rawSession, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
        SttStreamTicket ticket = (SttStreamTicket) rawSession.getAttributes().get(SttStreamHandshakeInterceptor.TICKET_ATTRIBUTE);
        if (ticket == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        if (!ticketService.tryAcquireChannel()) {
            session.sendMessage(new TextMessage("{\"type\":\"error\",\"code\":\"STT_STREAM_BUSY\"}"));
            session.close(CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        SttStreamSession stream = new SttStreamSession(
                ticket, rtzr::open, sink(session), scheduler, ticketService.properties().maxDurationMs(),
                ticketService::releaseChannel, outcome -> onDone(ticket, outcome));
        rawSession.getAttributes().put(SESSION_ATTRIBUTE, stream);
        stream.start();
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SttStreamSession stream = streamOf(session);
        if (stream == null) {
            return;
        }
        ByteBuffer payload = message.getPayload();
        byte[] pcm = new byte[payload.remaining()];
        payload.get(pcm);
        stream.onClientAudio(pcm);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SttStreamSession stream = streamOf(session);
        if (stream != null && message.getPayload().contains("\"end\"")) {
            stream.requestEnd("client");
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("stt-stream.transport-error {}", exception.toString());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SttStreamSession stream = streamOf(session);
        if (stream != null) {
            stream.onClientClosed();
        }
    }

    private void onDone(SttStreamTicket ticket, SttStreamSession.Outcome outcome) {
        // 받아 적은 문장은 남기지 않고 길이·지연만 남긴다(Grafana: |= "stt-stream.done").
        log.info("stt-stream.done reason={} audio_ms={} end_to_final_ms={} connect_ms={} chars={}",
                outcome.reason(), outcome.audioMs(), outcome.endToFinalMs(), outcome.connectMs(), outcome.transcript().length());
        if (outcome.transcript().isEmpty()) {
            return;
        }
        // 배치 받아 적기(CompanionChatPipelineService.transcribe)와 같이 대화 원장에 받아 적은 문장을 남긴다 - DB 쓰기라
        // RTZR·앱 스레드를 막지 않게 따로 돈다.
        scheduler.execute(() -> {
            try {
                conversationRecordService.recordCompanionTranscript(
                        ticket.storyId(), ticket.sceneId(), outcome.transcript(), "ko", "audio/l16;rate=16000",
                        ticket.attribution());
            } catch (Exception error) {
                log.warn("stt-stream.record-failed error={}", error.toString());
            }
        });
    }

    private static SttStreamSession streamOf(WebSocketSession session) {
        return (SttStreamSession) session.getAttributes().get(SESSION_ATTRIBUTE);
    }

    private static SttStreamSession.ClientSink sink(WebSocketSession session) {
        return new SttStreamSession.ClientSink() {
            @Override
            public void send(String json) {
                if (!session.isOpen()) {
                    return;
                }
                try {
                    session.sendMessage(new TextMessage(json));
                } catch (IOException | RuntimeException error) {
                    log.debug("stt-stream.send-failed {}", error.toString());
                }
            }

            @Override
            public void close() {
                try {
                    session.close(CloseStatus.NORMAL);
                } catch (IOException | RuntimeException ignored) {
                    // 이미 닫혔다
                }
            }
        };
    }
}

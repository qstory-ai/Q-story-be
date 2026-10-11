package com.qstory.backend.provider.rtzr.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.util.RequestDeadline;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * RTZR(VITO) 스트리밍 STT(WebSocket) 클라이언트. 16kHz mono LINEAR16 PCM을 보내는 동안 중간 문장(final=false)과
 * 끝난 문장(final=true)을 받는다. 프로토콜(developers.rtzr.ai/docs/stt-streaming/websocket):
 * <ul>
 *   <li>wss://openapi.vito.ai/v1/transcribe:streaming?sample_rate=16000&amp;encoding=LINEAR16&amp;... + Authorization: bearer</li>
 *   <li>오디오는 binary 메시지, 끝은 text "EOS", 지금 말을 바로 끊어 확정하려면 text {"type":"Finalize"}</li>
 *   <li>결과: {seq, start_at, duration, final, alternatives:[{text}]} - 첫 alternative가 가장 정확</li>
 * </ul>
 * 측정해 보니 EOS 뒤 연결이 닫히기까지 ~2초 걸리지만 Finalize는 ~50ms 만에 확정 문장을 준다 - 그래서 말이 끝나면
 * Finalize로 확정을 받고 EOS·닫기는 뒤에서 한다(SttStreamSession).
 */
@Component
public class RtzrStreamingClient {

    private static final Logger log = LoggerFactory.getLogger(RtzrStreamingClient.class);
    private static final String STREAM_URL = "wss://openapi.vito.ai/v1/transcribe:streaming";
    static final int SAMPLE_RATE = 16_000;
    /** 키워드는 쿼리 문자열 하나에 실린다 - 너무 길면 URL이 커지므로 앞에서부터 이만큼만. */
    static final int MAX_KEYWORDS = 50;

    /** 열린 스트림에 할 수 있는 일. 보내기는 순서대로 하나씩 나간다(java.net.http.WebSocket은 겹쳐 보내면 던진다). */
    public interface Stream {
        void sendAudio(byte[] pcm);

        /** 지금까지 들은 말을 바로 확정해 달라 - 확정할 말이 없으면 RTZR은 아무것도 보내지 않는다. */
        void finalizeUtterance();

        /** 오디오 끝 - RTZR이 남은 확정 문장을 보내고 연결을 닫는다. */
        void endOfStream();

        void abort();
    }

    public interface Listener {
        void onResult(Result result);

        /** 정상이든 아니든 연결이 끝났다(한 번만). */
        void onClosed(int statusCode, String reason);

        void onError(Throwable error);
    }

    public record Result(int seq, boolean isFinal, String text) {}

    /** RTZR이 보낸 결과 한 건을 읽는다. 알아볼 수 없으면 null. */
    static Result parseResult(ObjectMapper objectMapper, String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.has("alternatives")) {
                return null;
            }
            JsonNode first = node.path("alternatives").path(0);
            return new Result(node.path("seq").asInt(0), node.path("final").asBoolean(false), first.path("text").asText("").trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    /** RTZR 스트리밍 키워드는 "단어:점수"를 쉼표로 이은 문자열 하나다 - 구분자로 쓰이는 쉼표·콜론은 지운다. */
    static String keywordParam(List<String> keywords) {
        if (keywords == null) {
            return "";
        }
        return keywords.stream()
                .map(keyword -> keyword == null ? "" : keyword.replace(",", " ").replace(":", " ").trim())
                .filter(keyword -> !keyword.isEmpty() && keyword.length() <= 20)
                .distinct()
                .limit(MAX_KEYWORDS)
                .collect(Collectors.joining(","));
    }

    static URI streamUri(List<String> keywords) {
        StringBuilder query = new StringBuilder()
                .append("sample_rate=").append(SAMPLE_RATE)
                .append("&encoding=LINEAR16")
                .append("&model_name=sommers_ko")
                // 배치(RtzrSttClient.submit)와 같은 후처리 - 숫자 정규화, 군말·비속어 거르기. 배치 결과에는 문장부호(?)가
                // 붙어 나오므로 스트리밍도 켠다(기본값 false).
                .append("&use_itn=true")
                .append("&use_punctuation=true")
                .append("&use_disfluency_filter=true")
                .append("&use_profanity_filter=true");
        String keywordParam = keywordParam(keywords);
        if (!keywordParam.isEmpty()) {
            query.append("&keywords=").append(URLEncoder.encode(keywordParam, StandardCharsets.UTF_8));
        }
        return URI.create(STREAM_URL + "?" + query);
    }

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RtzrSttClient batchClient;

    public RtzrStreamingClient(HttpClient httpClient, ObjectMapper objectMapper, RtzrSttClient batchClient) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.batchClient = batchClient;
    }

    /** 토큰을 받고(캐시) RTZR 스트림을 연다. 인증·연결 실패는 실패한 future로 돌아온다. */
    public CompletableFuture<Stream> open(List<String> keywords, Listener listener) {
        return CompletableFuture
                .supplyAsync(() -> batchClient.accessToken(RequestDeadline.startingNow(10_000)))
                .thenCompose(token -> httpClient.newWebSocketBuilder()
                        .header("Authorization", "bearer " + token)
                        .connectTimeout(Duration.ofSeconds(5))
                        .buildAsync(streamUri(keywords), new SocketListener(listener))
                        .orTimeout(6, TimeUnit.SECONDS))
                .thenApply(OpenStream::new);
    }

    private final class SocketListener implements WebSocket.Listener {
        private final Listener listener;
        private final StringBuilder textBuffer = new StringBuilder();
        private boolean closed;

        SocketListener(Listener listener) {
            this.listener = listener;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String message = textBuffer.toString();
                textBuffer.setLength(0);
                Result result = parseResult(objectMapper, message);
                if (result != null) {
                    listener.onResult(result);
                } else {
                    log.warn("rtzr-stream.unknown-message length={}", message.length());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            finish(statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            listener.onError(error);
            finish(-1, "error");
        }

        private synchronized void finish(int statusCode, String reason) {
            if (closed) {
                return;
            }
            closed = true;
            listener.onClosed(statusCode, reason);
        }
    }

    private static final class OpenStream implements Stream {
        private final WebSocket socket;
        private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

        OpenStream(WebSocket socket) {
            this.socket = socket;
        }

        private synchronized void enqueue(java.util.function.Supplier<CompletableFuture<WebSocket>> send) {
            if (socket.isOutputClosed()) {
                return;
            }
            sendChain = sendChain.thenCompose(ignored -> socket.isOutputClosed()
                    ? CompletableFuture.completedFuture(null)
                    : send.get()).exceptionally(error -> null);
        }

        @Override
        public void sendAudio(byte[] pcm) {
            ByteBuffer buffer = ByteBuffer.wrap(pcm);
            enqueue(() -> socket.sendBinary(buffer, true));
        }

        @Override
        public void finalizeUtterance() {
            enqueue(() -> socket.sendText("{\"type\":\"Finalize\"}", true));
        }

        @Override
        public void endOfStream() {
            enqueue(() -> socket.sendText("EOS", true));
        }

        @Override
        public void abort() {
            socket.abort();
        }
    }
}

package com.qstory.backend.sttstream;

import com.qstory.backend.provider.rtzr.util.RtzrStreamingClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 앱 연결 하나 ↔ RTZR 스트림 하나를 잇는다. 앱이 보내는 PCM을 RTZR로 넘기고(연결되기 전 오디오는 모아 뒀다가 보낸다),
 * RTZR의 중간 문장을 {"type":"partial"}로 돌려준다. 앱이 {"type":"end"}를 보내면(말이 끝남) 확정을 받아
 * {"type":"final"} 한 번을 보내고 닫는다.
 *
 * <p>끝내는 방법이 지연을 정한다 - RTZR은 EOS 뒤 연결을 닫기까지 ~2초가 걸리지만, 아이가 말을 멈추고 앱이 1.5초 조용함을
 * 기다리는 동안 RTZR은 이미 그 말을 확정(final)해 둔다. 그래서 끝 요청 때 확정 안 된 중간 문장이 없으면 바로 답하고,
 * 있으면 {"type":"Finalize"}로 확정을 받아(~50ms) 답한다. EOS와 연결 닫기는 답한 뒤 뒤에서 한다.
 *
 * <p>모든 상태 변경은 synchronized - 앱 메시지(Tomcat 스레드), RTZR 결과(HttpClient 스레드), 타이머가 섞여 들어온다.
 */
final class SttStreamSession implements RtzrStreamingClient.Listener {

    private static final Logger log = LoggerFactory.getLogger(SttStreamSession.class);
    static final int BYTES_PER_SECOND = 16_000 * 2;
    /** Finalize를 보낸 뒤 확정 문장을 기다리는 시간 - 넘으면 마지막 중간 문장까지 넣어 답한다. */
    static final long FINALIZE_WAIT_MS = 1_500;
    /**
     * 마지막 결과 뒤로 이만큼(오디오 길이) 안 됐으면 아직 결과로 안 나온 끝말이 있을 수 있다 - "다 말했어"를 말끝에 바로 누른
     * 경우. 그때는 확정할 말이 있는지 Finalize로 묻고 짧게(FINALIZE_PROBE_WAIT_MS) 기다린다. 앱의 1.5초 조용함 자동 종료는
     * 이 값을 넘으므로 바로 답한다.
     */
    static final long RECENT_AUDIO_MS = 800;
    static final long FINALIZE_PROBE_WAIT_MS = 400;
    /** 끝 요청부터 답할 때까지의 상한(RTZR 연결이 늦게 열린 경우 포함). 앱은 이보다 조금 더 기다린 뒤 배치로 간다. */
    static final long END_DEADLINE_MS = 4_000;
    /** 답한 뒤 EOS를 보내고 RTZR이 스스로 닫기를 기다리는 시간 - 넘으면 끊는다(동시 채널을 오래 잡지 않게). */
    static final long CLOSE_GRACE_MS = 3_000;

    /** 앱 쪽 연결에 보내기·닫기. */
    interface ClientSink {
        void send(String json);

        void close();
    }

    record Outcome(String transcript, String reason, long audioMs, long endToFinalMs, long connectMs) {}

    private enum State { STREAMING, ENDING, DONE }

    private final SttStreamTicket ticket;
    private final BiFunction<List<String>, RtzrStreamingClient.Listener, CompletableFuture<RtzrStreamingClient.Stream>> connector;
    private final ClientSink client;
    private final ScheduledExecutorService scheduler;
    private final Runnable releaseChannel;
    private final Consumer<Outcome> onDone;
    private final long maxDurationMs;
    private final long maxAudioBytes;

    private final AtomicBoolean released = new AtomicBoolean();
    private final List<byte[]> pendingAudio = new ArrayList<>();
    private final Map<Integer, String> finals = new TreeMap<>();
    private State state = State.STREAMING;
    private RtzrStreamingClient.Stream stream;
    private boolean finalizeSent;
    private Integer partialSeq;
    private String partialText;
    private long audioBytes;
    private long audioBytesAtLastResult;
    private long startedAtNanos;
    private long connectMs = -1;
    private long endRequestedAtNanos;
    private String endReason;
    private final List<ScheduledFuture<?>> timers = new ArrayList<>();

    SttStreamSession(
            SttStreamTicket ticket,
            BiFunction<List<String>, RtzrStreamingClient.Listener, CompletableFuture<RtzrStreamingClient.Stream>> connector,
            ClientSink client, ScheduledExecutorService scheduler, long maxDurationMs, Runnable releaseChannel,
            Consumer<Outcome> onDone) {
        this.ticket = ticket;
        this.connector = connector;
        this.client = client;
        this.scheduler = scheduler;
        this.maxDurationMs = maxDurationMs;
        this.maxAudioBytes = BYTES_PER_SECOND * (maxDurationMs / 1000 + 1);
        this.releaseChannel = releaseChannel;
        this.onDone = onDone;
    }

    void start() {
        synchronized (this) {
            startedAtNanos = System.nanoTime();
            timers.add(scheduler.schedule(() -> requestEnd("max_duration"), maxDurationMs, TimeUnit.MILLISECONDS));
        }
        CompletableFuture<RtzrStreamingClient.Stream> opening;
        try {
            opening = connector.apply(ticket.sttKeywords(), this);
        } catch (RuntimeException error) {
            opening = CompletableFuture.failedFuture(error);
        }
        opening.whenComplete(this::onOpened);
    }

    private synchronized void onOpened(RtzrStreamingClient.Stream opened, Throwable error) {
        if (error != null || opened == null) {
            log.warn("stt-stream.rtzr-connect-failed error={}", error == null ? "null" : rootMessage(error));
            release();
            fail("STT_STREAM_CONNECT_FAILED");
            return;
        }
        if (state == State.DONE) {
            opened.abort();
            release();
            return;
        }
        stream = opened;
        connectMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
        for (byte[] chunk : pendingAudio) {
            stream.sendAudio(chunk);
        }
        pendingAudio.clear();
        client.send("{\"type\":\"ready\"}");
        if (state == State.ENDING) {
            finishOrFinalize();
        }
    }

    synchronized void onClientAudio(byte[] pcm) {
        if (state != State.STREAMING || pcm.length == 0) {
            return;
        }
        if (audioBytes + pcm.length > maxAudioBytes) {
            requestEnd("max_audio");
            return;
        }
        audioBytes += pcm.length;
        if (stream == null) {
            pendingAudio.add(pcm);
        } else {
            stream.sendAudio(pcm);
        }
    }

    /** 앱이 말이 끝났다고 알렸다(또는 최대 길이) - 확정 문장을 받아 답한다. */
    synchronized void requestEnd(String reason) {
        if (state != State.STREAMING) {
            return;
        }
        state = State.ENDING;
        endReason = reason;
        endRequestedAtNanos = System.nanoTime();
        timers.add(scheduler.schedule(this::complete, END_DEADLINE_MS, TimeUnit.MILLISECONDS));
        if (stream != null) {
            finishOrFinalize();
        }
    }

    private void finishOrFinalize() {
        boolean pendingPartial = partialText != null;
        boolean nothingYet = finals.isEmpty() && audioBytes > 0;
        boolean recentAudio = !finals.isEmpty()
                && (audioBytes - audioBytesAtLastResult) * 1000 / BYTES_PER_SECOND < RECENT_AUDIO_MS;
        if (!pendingPartial && !nothingYet && !recentAudio) {
            complete();
            return;
        }
        // 확정 안 된 말이 있다(또는 아직 결과가 없거나, 결과로 안 나온 끝말이 있을 수 있다) - 바로 확정해 달라고 한다.
        // 확정할 말이 없으면 RTZR은 답하지 않으므로 기다리는 시간에 상한을 둔다.
        finalizeSent = true;
        stream.finalizeUtterance();
        long wait = pendingPartial || nothingYet ? FINALIZE_WAIT_MS : FINALIZE_PROBE_WAIT_MS;
        timers.add(scheduler.schedule(this::complete, wait, TimeUnit.MILLISECONDS));
    }

    @Override
    public synchronized void onResult(RtzrStreamingClient.Result result) {
        if (state == State.DONE) {
            return;
        }
        audioBytesAtLastResult = audioBytes;
        if (result.isFinal()) {
            if (!result.text().isEmpty()) {
                finals.put(result.seq(), result.text());
            }
            if (partialSeq == null || partialSeq <= result.seq()) {
                partialSeq = null;
                partialText = null;
            }
        } else {
            partialSeq = result.seq();
            partialText = result.text().isEmpty() ? null : result.text();
        }
        if (state == State.ENDING && finalizeSent && partialText == null && !finals.isEmpty()) {
            complete();
            return;
        }
        client.send(message("partial", currentText()));
    }

    @Override
    public synchronized void onClosed(int statusCode, String reason) {
        release();
        if (state == State.DONE) {
            return;
        }
        if (state == State.ENDING) {
            complete();
            return;
        }
        log.warn("stt-stream.rtzr-closed-early status={} reason={}", statusCode, reason);
        fail("STT_STREAM_PROVIDER_CLOSED");
    }

    @Override
    public void onError(Throwable error) {
        log.warn("stt-stream.rtzr-error error={}", rootMessage(error));
    }

    /** 앱이 연결을 끊었다 - RTZR도 바로 닫는다. */
    synchronized void onClientClosed() {
        if (state == State.DONE) {
            return;
        }
        state = State.DONE;
        cancelTimers();
        if (stream != null) {
            stream.abort();
        }
        release();
        log.info("stt-stream.client-closed audio_ms={}", audioBytes * 1000 / BYTES_PER_SECOND);
    }

    private synchronized void complete() {
        if (state == State.DONE) {
            return;
        }
        if (state == State.STREAMING) {
            // END_DEADLINE 타이머 외에는 STREAMING에서 오지 않는다 - 그래도 끝 요청으로 본다.
            endReason = endReason == null ? "deadline" : endReason;
            endRequestedAtNanos = System.nanoTime();
        }
        state = State.DONE;
        cancelTimers();
        String transcript = currentText();
        long endToFinalMs = (System.nanoTime() - endRequestedAtNanos) / 1_000_000;
        if (transcript.isEmpty()) {
            client.send("{\"type\":\"final\",\"ok\":false,\"code\":\"NO_SPEECH_DETECTED\"}");
        } else {
            client.send(message("final", transcript));
        }
        client.close();
        if (stream != null) {
            RtzrStreamingClient.Stream closing = stream;
            closing.endOfStream();
            scheduler.schedule(() -> {
                closing.abort();
                release();
            }, CLOSE_GRACE_MS, TimeUnit.MILLISECONDS);
        } else {
            release();
        }
        onDone.accept(new Outcome(transcript, endReason, audioBytes * 1000 / BYTES_PER_SECOND, endToFinalMs, connectMs));
    }

    private synchronized void fail(String code) {
        if (state == State.DONE) {
            return;
        }
        state = State.DONE;
        cancelTimers();
        client.send("{\"type\":\"error\",\"code\":\"" + code + "\"}");
        client.close();
        if (stream != null) {
            stream.abort();
        }
        release();
    }

    private String currentText() {
        List<String> parts = new ArrayList<>(finals.values());
        if (partialText != null) {
            parts.add(partialText);
        }
        return String.join(" ", parts).trim();
    }

    private void cancelTimers() {
        timers.forEach(timer -> timer.cancel(false));
        timers.clear();
    }

    private void release() {
        if (released.compareAndSet(false, true)) {
            releaseChannel.run();
        }
    }

    private static String message(String type, String text) {
        StringBuilder json = new StringBuilder("{\"type\":\"").append(type).append('"');
        if ("final".equals(type)) {
            json.append(",\"ok\":true,\"transcript\":");
        } else {
            json.append(",\"text\":");
        }
        return json.append(jsonString(text)).append('}').toString();
    }

    static String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}

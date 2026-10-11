package com.qstory.backend.sttstream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qstory.backend.common.enums.ConversationInputMode;
import com.qstory.backend.conversationrecord.ConversationAttribution;
import com.qstory.backend.provider.rtzr.util.RtzrStreamingClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SttStreamSessionTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger clientCloses = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();
    private final AtomicReference<SttStreamSession.Outcome> outcome = new AtomicReference<>();
    private final FakeStream stream = new FakeStream();
    private final CompletableFuture<RtzrStreamingClient.Stream> opening = new CompletableFuture<>();
    private final AtomicReference<List<String>> keywordsSeen = new AtomicReference<>();

    @AfterEach
    void stop() {
        scheduler.shutdownNow();
    }

    private SttStreamSession session() {
        SttStreamTicket ticket = new SttStreamTicket(
                "t", "story", "scene", List.of("그레텔"),
                ConversationAttribution.of(null, null, null, null, ConversationInputMode.VOICE, null),
                Instant.now().plusSeconds(30));
        SttStreamSession session = new SttStreamSession(
                ticket,
                (keywords, listener) -> {
                    keywordsSeen.set(keywords);
                    return opening;
                },
                new SttStreamSession.ClientSink() {
                    @Override
                    public void send(String json) {
                        sent.add(json);
                    }

                    @Override
                    public void close() {
                        clientCloses.incrementAndGet();
                    }
                },
                scheduler, 35_000, releases::incrementAndGet, outcome::set);
        session.start();
        return session;
    }

    @Test
    void audioBeforeProviderOpensIsBufferedThenFlushedInOrder() {
        SttStreamSession session = session();
        session.onClientAudio(new byte[] {1});
        session.onClientAudio(new byte[] {2});
        assertTrue(stream.audio.isEmpty());

        opening.complete(stream);
        session.onClientAudio(new byte[] {3});

        assertEquals(List.of((byte) 1, (byte) 2, (byte) 3), stream.audio.stream().map(chunk -> chunk[0]).toList());
        assertEquals("{\"type\":\"ready\"}", sent.get(0));
        assertEquals(List.of("그레텔"), keywordsSeen.get());
    }

    @Test
    void partialsCarryFinalizedTextPlusCurrentPartial() {
        SttStreamSession session = session();
        opening.complete(stream);
        session.onResult(new RtzrStreamingClient.Result(0, true, "안녕"));
        session.onResult(new RtzrStreamingClient.Result(1, false, "그레텔"));

        assertEquals("{\"type\":\"partial\",\"text\":\"안녕 그레텔\"}", sent.get(sent.size() - 1));
    }

    @Test
    void endWithEverythingFinalizedAnswersImmediatelyWithoutFinalize() {
        SttStreamSession session = session();
        opening.complete(stream);
        session.onClientAudio(new byte[3200]);
        session.onResult(new RtzrStreamingClient.Result(0, false, "마녀는"));
        session.onResult(new RtzrStreamingClient.Result(0, true, "마녀는 왜"));
        // 앱의 무음 자동 종료 - 확정 뒤 1.5초 조용한 소리가 더 온다.
        session.onClientAudio(new byte[SttStreamSession.BYTES_PER_SECOND * 3 / 2]);

        session.requestEnd("client");

        assertEquals("{\"type\":\"final\",\"ok\":true,\"transcript\":\"마녀는 왜\"}", sent.get(sent.size() - 1));
        assertFalse(stream.finalized);
        assertTrue(stream.eos);
        assertEquals(1, clientCloses.get());
        assertEquals("마녀는 왜", outcome.get().transcript());
        assertEquals("client", outcome.get().reason());
        // 채널은 RTZR이 닫힐 때 돌려준다.
        assertEquals(0, releases.get());
        session.onClosed(1000, "");
        assertEquals(1, releases.get());
    }

    @Test
    void endWithPendingPartialAsksProviderToFinalizeAndAnswersOnFinal() {
        SttStreamSession session = session();
        opening.complete(stream);
        session.onClientAudio(new byte[3200]);
        session.onResult(new RtzrStreamingClient.Result(0, true, "우리 같이"));
        session.onResult(new RtzrStreamingClient.Result(1, false, "숲 밖으로"));

        session.requestEnd("client");
        assertTrue(stream.finalized);
        assertTrue(sent.stream().noneMatch(message -> message.contains("\"final\"")));

        session.onResult(new RtzrStreamingClient.Result(1, true, "숲 밖으로 나가 보자"));

        assertEquals("{\"type\":\"final\",\"ok\":true,\"transcript\":\"우리 같이 숲 밖으로 나가 보자\"}", sent.get(sent.size() - 1));
        assertTrue(stream.eos);
    }

    @Test
    void endRightAfterTheLastResultProbesForATrailingWord() {
        SttStreamSession session = session();
        opening.complete(stream);
        session.onClientAudio(new byte[3200]);
        session.onResult(new RtzrStreamingClient.Result(0, true, "엄마는 지금"));

        session.requestEnd("client");
        assertTrue(stream.finalized);
        session.onResult(new RtzrStreamingClient.Result(1, true, "어디에 있어"));

        assertEquals("{\"type\":\"final\",\"ok\":true,\"transcript\":\"엄마는 지금 어디에 있어\"}", sent.get(sent.size() - 1));
    }

    @Test
    void probeWithNothingLeftAnswersAfterAShortWait() throws Exception {
        SttStreamSession session = session();
        opening.complete(stream);
        session.onClientAudio(new byte[3200]);
        session.onResult(new RtzrStreamingClient.Result(0, true, "엄마는 지금 어디에 있어"));

        session.requestEnd("client");
        assertTrue(stream.finalized);
        Thread.sleep(SttStreamSession.FINALIZE_PROBE_WAIT_MS + 300);

        assertEquals("{\"type\":\"final\",\"ok\":true,\"transcript\":\"엄마는 지금 어디에 있어\"}", sent.get(sent.size() - 1));
    }

    @Test
    void endBeforeProviderOpensFinalizesOnceOpenedAndBufferedAudioIsSent() {
        SttStreamSession session = session();
        session.onClientAudio(new byte[3200]);
        session.requestEnd("client");
        assertFalse(stream.finalized);

        opening.complete(stream);

        assertEquals(1, stream.audio.size());
        assertTrue(stream.finalized);
        session.onResult(new RtzrStreamingClient.Result(0, true, "엄마는 어디 있어"));
        assertEquals("{\"type\":\"final\",\"ok\":true,\"transcript\":\"엄마는 어디 있어\"}", sent.get(sent.size() - 1));
    }

    @Test
    void endWithoutAnyAudioIsNoSpeech() {
        SttStreamSession session = session();
        opening.complete(stream);

        session.requestEnd("client");

        assertEquals("{\"type\":\"final\",\"ok\":false,\"code\":\"NO_SPEECH_DETECTED\"}", sent.get(sent.size() - 1));
        assertEquals("", outcome.get().transcript());
    }

    @Test
    void providerConnectFailureTellsClientToFallBackAndReleasesChannelOnce() {
        session();
        opening.completeExceptionally(new IllegalStateException("429"));

        assertEquals("{\"type\":\"error\",\"code\":\"STT_STREAM_CONNECT_FAILED\"}", sent.get(sent.size() - 1));
        assertEquals(1, clientCloses.get());
        assertEquals(1, releases.get());
    }

    @Test
    void providerClosingMidStreamIsAnError() {
        SttStreamSession session = session();
        opening.complete(stream);

        session.onClosed(1008, "stream timeout");

        assertEquals("{\"type\":\"error\",\"code\":\"STT_STREAM_PROVIDER_CLOSED\"}", sent.get(sent.size() - 1));
        assertEquals(1, releases.get());
    }

    @Test
    void clientDisconnectAbortsProviderAndReleasesChannel() {
        SttStreamSession session = session();
        opening.complete(stream);

        session.onClientClosed();
        session.onClosed(1006, "aborted");

        assertTrue(stream.aborted);
        assertEquals(1, releases.get());
        session.onClientAudio(new byte[] {9});
        assertTrue(stream.audio.isEmpty());
    }

    @Test
    void providerOpeningAfterClientLeftIsAbortedImmediately() {
        SttStreamSession session = session();
        session.onClientClosed();

        opening.complete(stream);

        assertTrue(stream.aborted);
        assertEquals(1, releases.get());
    }

    @Test
    void audioPastTheDurationCapEndsTheStream() {
        SttStreamSession session = session();
        opening.complete(stream);
        byte[] second = new byte[SttStreamSession.BYTES_PER_SECOND];
        for (int i = 0; i < 40; i++) {
            session.onClientAudio(second);
        }
        assertEquals(36, stream.audio.size());
        // 결과가 아직 없으니 RTZR에 바로 확정을 요청한다.
        assertTrue(stream.finalized);
    }

    @Test
    void jsonStringEscapesQuotesAndControlCharacters() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", SttStreamSession.jsonString("a\"b\\c\n\u0001"));
    }

    private static final class FakeStream implements RtzrStreamingClient.Stream {
        final List<byte[]> audio = new ArrayList<>();
        boolean finalized;
        boolean eos;
        boolean aborted;

        @Override
        public void sendAudio(byte[] pcm) {
            audio.add(pcm);
        }

        @Override
        public void finalizeUtterance() {
            finalized = true;
        }

        @Override
        public void endOfStream() {
            eos = true;
        }

        @Override
        public void abort() {
            aborted = true;
        }
    }
}

package com.qstory.backend.provider.gemini.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.common.error.ProviderException;
import com.qstory.backend.common.util.PcmLoudness;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.provider.openrouter.SynthesizedAudioStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 그레텔 답 음성 스트리밍 - Gemini SSE를 받는 대로 PCM으로 내보내고, 첫 소리 전 실패는 예외로 올린다. */
class GeminiTtsStreamingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static String audioEvent(byte[] pcm, String mimeType) {
        return "data: {\"candidates\": [{\"content\": {\"parts\": [{\"inlineData\": {\"mimeType\": \"" + mimeType
                + "\",\"data\": \"" + Base64.getEncoder().encodeToString(pcm) + "\"}}],\"role\": \"model\"},\"index\": 0}]}\r\n\r\n";
    }

    private static InputStream sse(String... events) {
        return new ByteArrayInputStream(String.join("", events).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void decodesEachEventAsItArrivesAndReadsTheSampleRate() throws IOException {
        List<GeminiSsePcmStream.Summary> finished = new ArrayList<>();
        GeminiSsePcmStream stream = new GeminiSsePcmStream(
                sse(audioEvent(new byte[] {1, 2, 3, 4}, "audio/l16; rate=16000; channels=1"),
                        audioEvent(new byte[] {5, 6}, "audio/l16; rate=16000; channels=1")),
                objectMapper, 24_000, finished::add);

        assertThat(stream.awaitFirstAudio()).isTrue();
        assertThat(stream.sampleRate()).isEqualTo(16_000);
        byte[] buffer = new byte[8192];
        // 지금 가진 조각만 내준다 - 다음 조각을 기다리며 앞 소리를 붙잡지 않는다.
        assertThat(stream.read(buffer, 0, buffer.length)).isEqualTo(4);
        assertThat(stream.read(buffer, 0, buffer.length)).isEqualTo(2);
        assertThat(buffer[0]).isEqualTo((byte) 5);
        assertThat(stream.read(buffer, 0, buffer.length)).isEqualTo(-1);
        assertThat(finished).containsExactly(new GeminiSsePcmStream.Summary(6, true));
        stream.close();
        assertThat(finished).hasSize(1);
    }

    @Test
    void errorEventMidStreamEndsTheStream() throws IOException {
        GeminiSsePcmStream stream = new GeminiSsePcmStream(
                sse(audioEvent(new byte[] {1, 2}, "audio/l16; rate=24000"),
                        "data: {\"error\": {\"code\": 500, \"status\": \"INTERNAL\"}}\n\n"),
                objectMapper, 24_000, summary -> {});

        assertThat(stream.awaitFirstAudio()).isTrue();
        assertThat(stream.read(new byte[10], 0, 10)).isEqualTo(2);
        assertThatThrownBy(() -> stream.read(new byte[10], 0, 10)).isInstanceOf(IOException.class);
    }

    @SuppressWarnings("unchecked")
    private GeminiTtsClient client(HttpClient httpClient) {
        AppProperties config = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(config.providers().gemini().apiKey()).thenReturn("key");
        when(config.providers().gemini().ttsModel()).thenReturn("gemini-3.1-flash-tts-preview");
        when(config.providers().gemini().ttsVoice()).thenReturn("Sulafat");
        return new GeminiTtsClient(httpClient, objectMapper, config, "key", true);
    }

    @SuppressWarnings("unchecked")
    private static HttpClient httpReturning(int status, InputStream body, ArgumentCaptor<HttpRequest> captor) throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(httpClient.send(captor.capture(), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);
        return httpClient;
    }

    @Test
    void streamsFromStreamGenerateContentWithTheCastVoice() throws Exception {
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        HttpClient httpClient = httpReturning(200, sse(audioEvent(new byte[] {9, 9}, "audio/l16; rate=24000")), request);

        SynthesizedAudioStream audio = client(httpClient).synthesizeStream(
                "line", "Leda", 1.0, RequestDeadline.startingNow(10_000));

        assertThat(request.getValue().uri().toString())
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-tts-preview:streamGenerateContent?alt=sse");
        assertThat(audio.mimeType()).isEqualTo("audio/pcm");
        assertThat(audio.sampleRate()).isEqualTo(24_000);
        assertThat(audio.stream().readAllBytes()).containsExactly(9, 9);
    }

    @Test
    void failureBeforeTheFirstAudioIsAProviderError() throws Exception {
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        GeminiTtsClient quotaHit = client(httpReturning(
                429, new ByteArrayInputStream("{\"error\":{\"code\":429}}".getBytes(StandardCharsets.UTF_8)), request));
        assertThatThrownBy(() -> quotaHit.synthesizeStream("line", "Leda", 1.0, RequestDeadline.startingNow(10_000)))
                .isInstanceOfSatisfying(ProviderException.class, error -> {
                    assertThat(error.code()).isEqualTo(ProviderErrorCode.GEMINI_TTS_FAILED);
                    assertThat(error.retryable()).isTrue();
                });

        GeminiTtsClient silent = client(httpReturning(200, sse("data: {\"candidates\": []}\n\n"), request));
        assertThatThrownBy(() -> silent.synthesizeStream("line", "Leda", 1.0, RequestDeadline.startingNow(10_000)))
                .isInstanceOfSatisfying(ProviderException.class,
                        error -> assertThat(error.code()).isEqualTo(ProviderErrorCode.GEMINI_TTS_EMPTY));
    }

    @Test
    void levelsTheStreamedVoiceToThePrerecordedLoudness() throws Exception {
        // 2초짜리 -23 LUFS 사인을 40ms 조각으로 - 맞추면 -17 LUFS 언저리가 된다.
        int rate = 24_000;
        byte[] pcm = new byte[rate * 2 * 2];
        for (int index = 0; index < pcm.length / 2; index++) {
            int sample = (int) Math.round(Math.sin(2 * Math.PI * 997 * index / rate) * 0.1 * 32767);
            pcm[index * 2] = (byte) sample;
            pcm[index * 2 + 1] = (byte) (sample >> 8);
        }
        List<String> events = new ArrayList<>();
        int chunk = rate / 25 * 2;
        for (int offset = 0; offset < pcm.length; offset += chunk) {
            events.add(audioEvent(java.util.Arrays.copyOfRange(pcm, offset, Math.min(pcm.length, offset + chunk)),
                    "audio/l16; rate=24000; channels=1"));
        }
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        HttpClient httpClient = httpReturning(200, sse(events.toArray(String[]::new)), request);
        AppProperties config = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(config.providers().gemini().ttsModel()).thenReturn("gemini-3.1-flash-tts-preview");
        when(config.providers().gemini().ttsVoice()).thenReturn("Sulafat");
        GeminiTtsClient leveling = new GeminiTtsClient(httpClient, objectMapper, config, "key", true, -17.0);

        byte[] out = leveling.synthesizeStream("line", "Leda", 1.0, RequestDeadline.startingNow(10_000))
                .stream().readAllBytes();

        assertThat(out).hasSameSizeAs(pcm);
        assertThat(PcmLoudness.integratedLufs(pcm, rate)).isCloseTo(-23.0, within(0.3));
        assertThat(PcmLoudness.integratedLufs(out, rate)).isCloseTo(-17.0, within(0.5));
    }
}

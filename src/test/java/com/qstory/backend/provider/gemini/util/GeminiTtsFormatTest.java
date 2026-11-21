package com.qstory.backend.provider.gemini.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.provider.audio.WavPcmUtil;
import com.qstory.backend.provider.openrouter.SynthesizedAudio;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 3.8 TTS 모델 이전(2026-11-21): 요청에 raw PCM 형식을 지정하고, WAV가 오면 헤더를 벗긴다. 모델은 인스턴스마다 정한다. */
class GeminiTtsFormatTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void stripsRiffHeaderToRawPcm() {
        byte[] pcm = {1, 2, 3, 4, 5, 6};
        byte[] wav = WavPcmUtil.wrapPcmAsWav(pcm, 24_000, 1, 16);

        assertThat(GeminiTtsClient.stripWavHeader(wav)).containsExactly(pcm);
    }

    @Test
    void leavesRawPcmUntouched() {
        byte[] pcm = {0, 0, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

        assertThat(GeminiTtsClient.stripWavHeader(pcm)).isSameAs(pcm);
    }

    @Test
    void readsToEndWhenDataSizeIsUnknown() {
        byte[] pcm = {1, 2, 3, 4};
        byte[] wav = WavPcmUtil.wrapPcmAsWav(pcm, 24_000, 1, 16);
        // 스트리밍 WAV처럼 data 크기 칸을 0으로 바꾼다.
        int dataSize = wav.length - pcm.length - 4;
        Arrays.fill(wav, dataSize, dataSize + 4, (byte) 0);

        assertThat(GeminiTtsClient.stripWavHeader(wav)).containsExactly(pcm);
    }

    @Test
    void synthesizeAsksForRawPcmWithTheInstanceModel() throws Exception {
        byte[] pcm = {10, 20, 30, 40};
        // 형식 지정이 무시돼 WAV가 와도 소리 앞에 헤더가 끼지 않아야 한다.
        String body = "{\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"audio\",\"mime_type\":\"audio/wav\",\"data\":\""
                + Base64.getEncoder().encodeToString(WavPcmUtil.wrapPcmAsWav(pcm, 24_000, 1, 16)) + "\"}]}]}";
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        HttpClient httpClient = httpReturning(body, captor);
        GeminiTtsClient client = new GeminiTtsClient(
                httpClient, objectMapper, config(), "gemini-3.8-flash-tts", "key", false, null);

        SynthesizedAudio audio = client.synthesize("안녕", null, 1.0, RequestDeadline.startingNow(30_000));

        assertThat(audio.audio()).containsExactly(WavPcmUtil.wrapPcmAsWav(pcm, 24_000, 1, 16));
        JsonNode sent = objectMapper.readTree(requestBody(captor.getValue()));
        assertThat(sent.path("model").asText()).isEqualTo("gemini-3.8-flash-tts");
        assertThat(sent.path("response_format").path("mime_type").asText()).isEqualTo("audio/l16");
        assertThat(sent.path("response_format").path("sample_rate").asInt()).isEqualTo(24_000);
        assertThat(sent.path("generation_config").path("speech_config").get(0).path("voice").asText()).isEqualTo("Sulafat");
    }

    @Test
    void narrationModelFallsBackToRealtimeModel() {
        assertThat(new AppProperties.Gemini("k", "lite", "flash", "Sulafat", "p").narrationTtsModelOrDefault()).isEqualTo("flash");
        assertThat(new AppProperties.Gemini("k", "lite", " ", "Sulafat", "p").narrationTtsModelOrDefault()).isEqualTo("lite");
        assertThat(new AppProperties.Gemini("k", "lite", null, "Sulafat", "p").narrationTtsModelOrDefault()).isEqualTo("lite");
    }

    private static AppProperties config() {
        AppProperties config = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(config.providers().gemini().ttsModel()).thenReturn("gemini-3.8-flash-lite-tts");
        when(config.providers().gemini().ttsVoice()).thenReturn("Sulafat");
        return config;
    }

    @SuppressWarnings("unchecked")
    private static HttpClient httpReturning(String body, ArgumentCaptor<HttpRequest> captor) throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(httpClient.send(captor.capture(), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);
        return httpClient;
    }

    /** HttpRequest의 BodyPublisher에서 보낸 JSON을 꺼낸다. */
    private static String requestBody(HttpRequest request) throws Exception {
        StringBuilder text = new StringBuilder();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) { text.append(StandardCharsets.UTF_8.decode(item)); }
            @Override public void onError(Throwable throwable) {}
            @Override public void onComplete() {}
        });
        return text.toString();
    }
}

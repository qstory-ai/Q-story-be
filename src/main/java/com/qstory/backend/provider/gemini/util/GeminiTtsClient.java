package com.qstory.backend.provider.gemini.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.common.error.AbortException;
import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.common.error.ProviderException;
import com.qstory.backend.common.util.PcmLoudness;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.common.util.WavPcmUtil;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.provider.openrouter.SynthesizedAudio;
import com.qstory.backend.provider.openrouter.SynthesizedAudioStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Gemini TTS를 직접 호출하는 클라이언트. google/gemini-*-tts-preview 모델과 Sulafat 등 프리셋 목소리는
 * Gemini 자체 API 전용이라 OpenRouter를 거치지 않는다.
 *
 * <p>synthesize()(전체를 한 번에)는 Interactions API를 쓴다. synthesizeStream()은 스트리밍이 켜져 있으면
 * (qstory.voice-streaming.tts) models/{model}:streamGenerateContent?alt=sse로 소리를 만들어지는 대로 내보낸다 -
 * 같은 모델·목소리·입력 문장이라 목소리는 같고, 2026-10 실측으로 첫 소리가 약 1.4초에 온다(전체를 기다리면 8~11초).
 * 호출은 여전히 답 하나에 한 번이다(하루 요청 수 한도 그대로). 꺼져 있으면 예전처럼 전체를 만든 뒤 감싸 보낸다.
 *
 * <p>loudnessTargetLufs가 있으면(qstory.voice-loudness) 두 길 모두 소리를 미리 녹음한 고정 음성과 같은 음량으로 맞춘다
 * (PcmLoudness) - Gemini가 내는 음량은 호출마다 -14.6 ~ -22.5 LUFS로 제각각이라 녹음한 대사 사이에서 튀었다.
 */
@Component
@Primary
public class GeminiTtsClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiTtsClient.class);
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/interactions";
    private static final String STREAM_URL_FORMAT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:streamGenerateContent?alt=sse";
    private static final int FAILURE_BODY_LOG_LIMIT = 500;
    private static final int PCM_SAMPLE_RATE = 24_000;
    private static final int PCM_CHANNELS = 1;
    private static final int PCM_BIT_DEPTH = 16;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String ttsModel;
    private final String ttsVoice;
    private final boolean streaming;
    /** null이면 음량을 맞추지 않는다(Gemini가 준 그대로). */
    private final Double loudnessTargetLufs;

    @Autowired
    public GeminiTtsClient(
            HttpClient httpClient, ObjectMapper objectMapper, AppProperties config,
            @Value("${qstory.voice-streaming.tts:true}") boolean streaming,
            @Value("${qstory.voice-loudness.enabled:true}") boolean loudnessEnabled,
            @Value("${qstory.voice-loudness.target-lufs:-17}") double loudnessTargetLufs) {
        this(httpClient, objectMapper, config, config.providers().gemini().apiKey(), streaming,
                loudnessEnabled ? loudnessTargetLufs : null);
    }

    public GeminiTtsClient(HttpClient httpClient, ObjectMapper objectMapper, AppProperties config) {
        this(httpClient, objectMapper, config, config.providers().gemini().apiKey(), false);
    }

    /**
     * 같은 모델/목소리 설정에 다른 API 키를 쓰는 인스턴스(prefetch 전용).
     * @param streaming synthesizeStream()이 소리를 만들어지는 대로 내보낼지(qstory.voice-streaming.tts)
     */
    public GeminiTtsClient(
            HttpClient httpClient, ObjectMapper objectMapper, AppProperties config, String apiKey, boolean streaming) {
        this(httpClient, objectMapper, config, apiKey, streaming, null);
    }

    /** @param loudnessTargetLufs 맞출 음량(LUFS) - null이면 맞추지 않는다. */
    public GeminiTtsClient(
            HttpClient httpClient, ObjectMapper objectMapper, AppProperties config, String apiKey, boolean streaming,
            Double loudnessTargetLufs) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.ttsModel = config.providers().gemini().ttsModel();
        this.ttsVoice = config.providers().gemini().ttsVoice();
        this.streaming = streaming;
        this.loudnessTargetLufs = loudnessTargetLufs;
    }

    private byte[] leveled(byte[] pcm) {
        return loudnessTargetLufs == null ? pcm : PcmLoudness.normalize(pcm, PCM_SAMPLE_RATE, loudnessTargetLufs);
    }

    /** speed는 호출부 호환용으로만 받는다 - Gemini Interactions API에 대응 파라미터가 없다. */
    public SynthesizedAudio synthesize(String text, String voice, double speed, RequestDeadline deadline) {
        byte[] pcm = leveled(fetchPcm("synthesize", text, voice, deadline));
        byte[] wav = WavPcmUtil.wrapPcmAsWav(pcm, PCM_SAMPLE_RATE, PCM_CHANNELS, PCM_BIT_DEPTH);
        return new SynthesizedAudio(wav, "audio/wav", null);
    }

    /**
     * raw PCM(WAV 헤더 없음) 스트림 - NarrationController가 받는 대로 내보낸다. 스트리밍이 켜져 있으면 첫 오디오
     * 조각이 올 때까지만 기다려 반환하므로, 그 전의 실패(4xx/5xx, 빈 응답)는 예전처럼 예외로 올라가 JSON 실패 응답이
     * 된다. 그 뒤의 실패는 소리가 거기서 끝나는 것으로 나타난다.
     */
    public SynthesizedAudioStream synthesizeStream(String text, String voice, double speed, RequestDeadline deadline) {
        if (streaming) {
            return openPcmStream(text, voice, deadline);
        }
        byte[] pcm = leveled(fetchPcm("synthesizeStream", text, voice, deadline));
        return new SynthesizedAudioStream(
                new ByteArrayInputStream(pcm), "audio/pcm", PCM_SAMPLE_RATE, PCM_CHANNELS, PCM_BIT_DEPTH, null);
    }

    private SynthesizedAudioStream openPcmStream(String text, String voice, RequestDeadline deadline) {
        long startedAt = System.nanoTime();
        InputStream body = null;
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    buildStreamHttpRequest(text, voice, deadline), HttpResponse.BodyHandlers.ofInputStream());
            body = response.body();
            if (response.statusCode() / 100 != 2) {
                byte[] failureBody = body.readNBytes(FAILURE_BODY_LOG_LIMIT * 4);
                logProviderHttpFailure("synthesizeStream", response.statusCode(), failureBody);
                throw new ProviderException(
                        ProviderErrorCode.GEMINI_TTS_FAILED, "답변 음성을 만들지 못했어요.", response.statusCode() >= 429);
            }
            long[] firstAudioMs = {-1};
            GeminiSsePcmStream pcm = new GeminiSsePcmStream(body, objectMapper, PCM_SAMPLE_RATE, summary ->
                    // 호출 한 번마다 한 줄 - Grafana가 하루 사용량을 한도와 비교한다(synthesize()와 같은 태그). 문장은 남기지 않는다.
                    log.info("gemini-tts.ok context=synthesizeStream model={} chars={} audio_bytes={} first_audio_ms={} total_ms={} completed={}",
                            ttsModel, text.length(), summary.audioBytes(), firstAudioMs[0],
                            (System.nanoTime() - startedAt) / 1_000_000, summary.completed()));
            // 소리가 멈춘 채 연결만 남는 경우를 대비해 요청 시간 예산이 끝나면 닫는다 - HttpClient의 timeout은
            // 응답 헤더까지만 적용된다. 닫으면 막혀 있던 read가 끝나고 클라이언트는 받은 데까지 듣는다.
            CompletableFuture.delayedExecutor(Math.max(1, deadline.remaining().toMillis()), TimeUnit.MILLISECONDS)
                    .execute(() -> closeQuietly(pcm));
            if (!pcm.awaitFirstAudio()) {
                closeQuietly(pcm);
                log.warn("gemini-tts.empty-audio context=synthesizeStream status={}", response.statusCode());
                throw new ProviderException(ProviderErrorCode.GEMINI_TTS_EMPTY, "답변 음성이 비어 있어요.");
            }
            firstAudioMs[0] = (System.nanoTime() - startedAt) / 1_000_000;
            InputStream audio = loudnessTargetLufs == null
                    ? pcm
                    : PcmLoudness.leveling(pcm, pcm.sampleRate(), loudnessTargetLufs);
            return new SynthesizedAudioStream(audio, "audio/pcm", pcm.sampleRate(), PCM_CHANNELS, PCM_BIT_DEPTH, null);
        } catch (ProviderException | AbortException known) {
            closeQuietly(body);
            throw known;
        } catch (HttpTimeoutException timeout) {
            closeQuietly(body);
            throw new AbortException("request-timeout");
        } catch (Exception error) {
            closeQuietly(body);
            log.warn("gemini-tts.stream-failed context=synthesizeStream reason={}", error.toString());
            throw new ProviderException(
                    ProviderErrorCode.GEMINI_TTS_NETWORK_FAILED, "답변 음성 서버에 연결하지 못했어요.", true, error);
        }
    }

    /** Interactions API(synthesize)와 같은 모델·목소리·입력 문장 - 목소리 설정이 두 길에서 갈리지 않게 한다. */
    private HttpRequest buildStreamHttpRequest(String text, String voice, RequestDeadline deadline) {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode content = body.putArray("contents").addObject();
        content.put("role", "user");
        content.putArray("parts").addObject().put("text", text);
        ObjectNode generationConfig = body.putObject("generationConfig");
        generationConfig.putArray("responseModalities").add("AUDIO");
        generationConfig.putObject("speechConfig").putObject("voiceConfig").putObject("prebuiltVoiceConfig")
                .put("voiceName", voice == null ? ttsVoice : voice);
        return deadline.applyTo(HttpRequest.newBuilder(URI.create(String.format(STREAM_URL_FORMAT, ttsModel)))
                        .header("x-goog-api-key", apiKey)
                        .header("content-type", "application/json")
                        .header("accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(
                                body.toString().getBytes(StandardCharsets.UTF_8))))
                .build();
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Exception ignored) {
            // 이미 닫혔거나 연결이 끊겼다.
        }
    }

    private byte[] fetchPcm(String context, String text, String voice, RequestDeadline deadline) {
        try {
            HttpRequest httpRequest = buildSpeechHttpRequest(text, voice, deadline);
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                logProviderHttpFailure(context, response.statusCode(), response.body());
                throw new ProviderException(
                        ProviderErrorCode.GEMINI_TTS_FAILED, "답변 음성을 만들지 못했어요.", response.statusCode() >= 429);
            }
            byte[] pcm = decodeAudioData(response.body());
            if (pcm.length == 0) {
                logEmptyAudio(context, response.statusCode(), response.body());
                throw new ProviderException(ProviderErrorCode.GEMINI_TTS_EMPTY, "답변 음성이 비어 있어요.");
            }
            // 호출 한 번마다 한 줄 - Grafana가 하루 사용량을 한도(무료 100회/일)와 비교한다. 문장은 남기지 않는다.
            log.info("gemini-tts.ok context={} model={} chars={} audio_bytes={}", context, ttsModel, text.length(), pcm.length);
            return pcm;
        } catch (ProviderException | AbortException known) {
            throw known;
        } catch (HttpTimeoutException timeout) {
            throw new AbortException("request-timeout");
        } catch (Exception error) {
            throw new ProviderException(
                    ProviderErrorCode.GEMINI_TTS_NETWORK_FAILED, "답변 음성 서버에 연결하지 못했어요.", true, error);
        }
    }

    /**
     * 응답 JSON에서 raw PCM(base64)을 뽑는다 - 실제 응답 구조는
     * {"steps":[{"content":[{"data":"<base64 pcm>"}]}]} 다.
     * steps/content 둘 다 배열이라 첫 번째 항목만 보지 않고 data가 채워진 첫 항목을 찾는다.
     */
    private byte[] decodeAudioData(byte[] responseBody) throws Exception {
        JsonNode payload = objectMapper.readTree(responseBody);
        for (JsonNode step : payload.path("steps")) {
            for (JsonNode content : step.path("content")) {
                String base64 = content.path("data").asText(null);
                if (base64 != null && !base64.isBlank()) {
                    return Base64.getDecoder().decode(base64);
                }
            }
        }
        return new byte[0];
    }

    private HttpRequest buildSpeechHttpRequest(String text, String voice, RequestDeadline deadline) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", ttsModel);
        body.put("input", text);
        body.putObject("response_format").put("type", "audio");
        ObjectNode generationConfig = body.putObject("generation_config");
        generationConfig.putArray("speech_config").addObject().put("voice", voice == null ? ttsVoice : voice);
        return deadline.applyTo(HttpRequest.newBuilder(URI.create(BASE_URL))
                        .header("x-goog-api-key", apiKey)
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(
                                body.toString().getBytes(StandardCharsets.UTF_8))))
                .build();
    }

    /**
     * 200인데도 decodeAudioData()가 오디오를 못 찾았을 때 실제 응답 구조를 남긴다 - steps/content
     * 배열이 비어 있거나 예상 밖 형태로 바뀌는 경우를 대비한 안전망이다. 필드 이름/구조를 보는 게
     * 목적이라 FAILURE_BODY_LOG_LIMIT보다 넉넉하게 남긴다.
     */
    private void logEmptyAudio(String context, int statusCode, byte[] responseBody) {
        String bodySnippet = new String(responseBody, StandardCharsets.UTF_8);
        int limit = FAILURE_BODY_LOG_LIMIT * 4;
        if (bodySnippet.length() > limit) {
            bodySnippet = bodySnippet.substring(0, limit) + "...(truncated)";
        }
        log.warn(
                "gemini-tts.empty-audio context={} status={} responseBody={}",
                context, statusCode, bodySnippet);
    }

    /**
     * 2xx가 아닌 실패의 실제 원인을 서버 로그에 남긴다 - OpenRouterClient.logProviderHttpFailure와
     * 같은 이유(사용자에게는 고정 안전 문구만 내려가므로, 이 로그가 없으면 401/400 같은 설정
     * 오류인지조차 운영 중엔 알 수 없다). API 키는 헤더에만 실리므로 본문을 그대로 남겨도 새지 않는다.
     */
    private void logProviderHttpFailure(String context, int statusCode, byte[] responseBody) {
        String bodySnippet = new String(responseBody, StandardCharsets.UTF_8);
        if (bodySnippet.length() > FAILURE_BODY_LOG_LIMIT) {
            bodySnippet = bodySnippet.substring(0, FAILURE_BODY_LOG_LIMIT) + "...(truncated)";
        }
        log.warn(
                "gemini-http.failed context={} status={} retryable={} responseBody={}",
                context, statusCode, statusCode >= 429, bodySnippet);
    }
}

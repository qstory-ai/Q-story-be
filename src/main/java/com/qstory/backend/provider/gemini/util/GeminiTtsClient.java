package com.qstory.backend.provider.gemini.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.common.error.AbortException;
import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.common.error.ProviderException;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.common.util.WavPcmUtil;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.provider.openrouter.SynthesizedAudio;
import com.qstory.backend.provider.openrouter.SynthesizedAudioStream;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Gemini의 Interactions API(generativelanguage.googleapis.com)를 직접 호출하는 TTS 클라이언트.
 * OpenRouterClient가 예전에 이 역할을 맡았지만, google/gemini-*-tts-preview 모델이 OpenRouter
 * 모델 카탈로그에 없어서(직접 조회로 확인) 매 요청이 OPENROUTER_TTS_FAILED로 실패했다 - Sulafat
 * 등 프리셋 목소리 이름도 Gemini 자체 API 전용이라 OpenRouter의 다른 provider로는 대체가 안 된다.
 *
 * <p>synthesizeStream()은 아직 진짜 청크 스트리밍이 아니다 - Gemini의 스트리밍 응답은 SSE
 * (event_type=step.delta, delta.type=audio)로 오는데, 정확한 필드 구조를 실제 API 키로
 * 검증하지 못한 상태라 잘못 파싱하면 무한 대기나 깨진 오디오로 조용히 실패할 위험이 있다.
 * 아이가 듣는 기능에 그런 위험을 감수하기보다, 우선 synthesize()로 전체를 합성한 뒤
 * ByteArrayInputStream으로 감싸 반환한다 - /v1/narrations/stream 엔드포인트 자체는 정상
 * 동작하지만, 스트리밍의 지연시간 이점(첫 오디오가 나올 때까지 기다리지 않는 것)은 아직 없다.
 * 실제 SSE 스키마를 확인하면 진짜 스트리밍으로 교체해야 한다.
 */
@Component
public class GeminiTtsClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiTtsClient.class);
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/interactions";
    private static final int FAILURE_BODY_LOG_LIMIT = 500;
    private static final int PCM_SAMPLE_RATE = 24_000;
    private static final int PCM_CHANNELS = 1;
    private static final int PCM_BIT_DEPTH = 16;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String ttsModel;
    private final String ttsVoice;

    public GeminiTtsClient(HttpClient httpClient, ObjectMapper objectMapper, AppProperties config) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = config.providers().gemini().apiKey();
        this.ttsModel = config.providers().gemini().ttsModel();
        this.ttsVoice = config.providers().gemini().ttsVoice();
    }

    public SynthesizedAudio synthesize(String text, String voice, double speed, RequestDeadline deadline) {
        try {
            HttpRequest httpRequest = buildSpeechHttpRequest(text, voice, deadline);
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                logProviderHttpFailure("synthesize", response.statusCode(), response.body());
                throw new ProviderException(
                        ProviderErrorCode.GEMINI_TTS_FAILED, "답변 음성을 만들지 못했어요.", response.statusCode() >= 429);
            }
            byte[] pcm = decodeAudioData(response.body());
            if (pcm.length == 0) {
                logEmptyAudio("synthesize", response.statusCode(), response.body());
                throw new ProviderException(ProviderErrorCode.GEMINI_TTS_EMPTY, "답변 음성이 비어 있어요.");
            }
            byte[] wav = WavPcmUtil.wrapPcmAsWav(pcm, PCM_SAMPLE_RATE, PCM_CHANNELS, PCM_BIT_DEPTH);
            return new SynthesizedAudio(wav, "audio/wav", null);
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
     * 진짜 청크 스트리밍이 아니라 synthesize()의 결과를 InputStream으로 감싼 것 - 클래스
     * 문서의 설명 참고. 반환 계약(SynthesizedAudioStream)은 NarrationController가 기대하는
     * 그대로(raw PCM, WAV 헤더 없음)라서 스트리밍 방식이 나중에 바뀌어도 호출부는 안 바뀐다.
     */
    public SynthesizedAudioStream synthesizeStream(String text, String voice, double speed, RequestDeadline deadline) {
        try {
            HttpRequest httpRequest = buildSpeechHttpRequest(text, voice, deadline);
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                logProviderHttpFailure("synthesizeStream", response.statusCode(), response.body());
                throw new ProviderException(
                        ProviderErrorCode.GEMINI_TTS_FAILED, "답변 음성을 만들지 못했어요.", response.statusCode() >= 429);
            }
            byte[] pcm = decodeAudioData(response.body());
            if (pcm.length == 0) {
                logEmptyAudio("synthesizeStream", response.statusCode(), response.body());
                throw new ProviderException(ProviderErrorCode.GEMINI_TTS_EMPTY, "답변 음성이 비어 있어요.");
            }
            return new SynthesizedAudioStream(
                    new ByteArrayInputStream(pcm), "audio/pcm", PCM_SAMPLE_RATE, PCM_CHANNELS, PCM_BIT_DEPTH, null);
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
     * 응답 JSON에서 raw PCM(base64)을 뽑는다 - 실제 프로덕션 로그로 확인한 구조는
     * {"steps":[{"content":[{"data":"<base64 pcm>"}]}]} 다(문서 요약만 보고 처음 짐작했던
     * interaction.output_audio.data는 틀렸었다 - 매번 200에 오디오는 빈 값으로 실패한 원인).
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
     * 배열이 비어 있거나 예상 밖 형태로 바뀌는 경우를 대비한 안전망이다(처음 이 클라이언트를 만들
     * 때는 이 로그 덕분에 잘못 짐작했던 필드 경로 interaction.output_audio.data를 실제 구조
     * steps[].content[].data로 바로잡을 수 있었다). base64 오디오 값 자체가 길 수 있어
     * FAILURE_BODY_LOG_LIMIT보다 넉넉하게 남긴다 - 필드 이름/구조를 보는 게 목적이라.
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

package com.qstory.backend.extension.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.AbortException;
import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.common.error.ProviderException;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.provider.audio.NormalizedAudio;
import com.qstory.backend.provider.rtzr.RtzrTranscriptionResult;
import com.qstory.backend.provider.rtzr.util.RtzrSttClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Optional companion-chat batch adapter. Never replaces streaming or silently falls back. */
public final class WhisperAddon {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private WhisperAddon() {}

    public static boolean ready(boolean legacyReady) {
        if (!ExtensionFlags.whisper()) return legacyReady;
        try { endpoint(); }
        catch (RuntimeException invalid) { return false; }
        return !ExtensionFlags.value("QSTORY_WHISPER_TOKEN", "").isBlank();
    }

    private static URI endpoint() {
        URI uri = URI.create(ExtensionFlags.value("QSTORY_WHISPER_URL", "http://127.0.0.1:7861/v1/transcribe"));
        boolean loopback = List.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost());
        boolean internal = ExtensionFlags.enabled("QSTORY_ALLOW_INTERNAL_HTTP");
        if (uri.getHost() == null || uri.getUserInfo() != null ||
                !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && (loopback || internal)))) {
            throw new IllegalArgumentException("Use HTTPS, loopback, or explicitly trusted internal HTTP");
        }
        return uri;
    }

    public static RtzrTranscriptionResult transcribe(
            RtzrSttClient legacy, NormalizedAudio audio, List<String> keywords, RequestDeadline deadline) {
        if (!ExtensionFlags.whisper()) {
            return legacy.transcribe(audio.audio(), audio.extension(), audio.mimeType(),
                    ExtensionFlags.speech() ? VocabularyHints.clean(keywords) : keywords, deadline);
        }
        deadline.requireTimeRemaining();
        try {
            if (audio.audio().length > 12_000_000) throw new IllegalArgumentException("Audio upload too large");
            String token = ExtensionFlags.value("QSTORY_WHISPER_TOKEN", "");
            if (token.isBlank()) throw new IllegalArgumentException("Missing QSTORY_WHISPER_TOKEN");
            Map<String, Object> body = Map.of(
                    "audio_base64", Base64.getEncoder().encodeToString(audio.audio()),
                    "mime_type", audio.mimeType(),
                    "keywords", VocabularyHints.clean(keywords));
            HttpRequest request = HttpRequest.newBuilder(endpoint())
                    .timeout(deadline.remaining())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            var future = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response;
            try {
                response = future.get(Math.max(1, deadline.remaining().toMillis()), TimeUnit.MILLISECONDS);
            } catch (TimeoutException | InterruptedException error) {
                future.cancel(true);
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new AbortException("request-timeout");
            }
            if (response.body().length() > 65536) throw new IllegalArgumentException("Response too large");
            if (response.statusCode() / 100 != 2) {
                throw new ProviderException(ProviderErrorCode.SPEECH_PIPELINE_FAILED,
                        "목소리 인식 연결을 확인하지 못했어. 잠시 후 다시 말하거나 글로 알려줘.", true);
            }
            JsonNode payload = JSON.readTree(response.body());
            if (payload == null || !payload.isObject() || !payload.path("transcript").isTextual()) {
                throw new IllegalArgumentException("Invalid transcription response schema");
            }
            String text = payload.path("transcript").asText("").trim();
            if (payload.path("needs_repeat").asBoolean(false) || text.isEmpty()) {
                throw new ProviderException(ProviderErrorCode.NO_SPEECH_DETECTED,
                        "내가 잘 못 들었어. 한 번만 다시 말하거나 글로 알려줄래?", true);
            }
            if (text.length() > 2000) throw new IllegalArgumentException("Transcript too large");
            return new RtzrTranscriptionResult(text, "ko",
                    "whisper:" + payload.path("model_id").asText("configured-model"));
        } catch (ProviderException | AbortException expected) {
            throw expected;
        } catch (Exception error) {
            // Do not log audio, transcript, token or response body.
            throw new ProviderException(ProviderErrorCode.SPEECH_PIPELINE_FAILED,
                    "목소리를 확인하지 못했어. 다시 말하거나 글로 알려줘.", true, error);
        }
    }
}

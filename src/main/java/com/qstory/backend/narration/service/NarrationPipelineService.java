package com.qstory.backend.narration.service;

import com.qstory.backend.common.error.AbortException;
import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.common.error.ProviderException;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.provider.ProviderReadiness;
import com.qstory.backend.provider.openrouter.util.OpenRouterClient;
import com.qstory.backend.provider.openrouter.SynthesizedAudio;
import com.qstory.backend.provider.openrouter.SynthesizedAudioStream;
import com.qstory.backend.question.dto.AudioPayload;
import com.qstory.backend.story.CastEntry;
import com.qstory.backend.story.service.StoryRegistryService.ResolvedNarrationContext;
import com.qstory.backend.voicecast.service.VoiceCastService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** narration-pipeline.mjs를 Java로 포팅한 것. */
@Service
public class NarrationPipelineService {

    private final AppProperties config;
    private final OpenRouterClient openRouterClient;
    private final VoiceCastService voiceCastService;

    public NarrationPipelineService(AppProperties config, OpenRouterClient openRouterClient, VoiceCastService voiceCastService) {
        this.config = config;
        this.openRouterClient = openRouterClient;
        this.voiceCastService = voiceCastService;
    }

    public Map<String, Object> process(ResolvedNarrationContext context, String storyId, String speakerId, String text, RequestDeadline deadline) {
        if (!ProviderReadiness.of(config).tts()) {
            return failure(ProviderErrorCode.NARRATION_PROVIDER_NOT_CONFIGURED, "캐릭터 음성을 준비하지 못했어요.");
        }
        CastEntry cast = context.cast();
        try {
            String ttsInput = voiceCastService.buildGeminiTtsPerformanceInput(storyId, speakerId, text);
            SynthesizedAudio generated = openRouterClient.synthesize(ttsInput, cast.voice(), 1.0, deadline);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("text", text);
            result.put("speakerId", speakerId);
            result.put("audio", AudioPayload.of(generated.mimeType(), generated.audio()));
            return result;
        } catch (AbortException abort) {
            return failure(ProviderErrorCode.NARRATION_TIMEOUT, "캐릭터 음성을 기다리는 시간이 길어졌어요.");
        } catch (ProviderException error) {
            return failureFrom(error, "캐릭터 음성을 준비하지 못했어요.");
        }
    }

    public record StreamResult(boolean ok, Map<String, Object> failure, SynthesizedAudioStream audio) {}

    public StreamResult processStream(ResolvedNarrationContext context, String storyId, String speakerId, String text, RequestDeadline deadline) {
        if (!ProviderReadiness.of(config).tts()) {
            return new StreamResult(false, failure(ProviderErrorCode.NARRATION_PROVIDER_NOT_CONFIGURED, "캐릭터 음성을 준비하지 못했어요."), null);
        }
        CastEntry cast = context.cast();
        try {
            String ttsInput = voiceCastService.buildGeminiTtsPerformanceInput(storyId, speakerId, text);
            SynthesizedAudioStream generated = openRouterClient.synthesizeStream(ttsInput, cast.voice(), 1.0, deadline);
            return new StreamResult(true, null, generated);
        } catch (AbortException abort) {
            return new StreamResult(false, failure(ProviderErrorCode.NARRATION_TIMEOUT, "캐릭터 음성을 기다리는 시간이 길어졌어요."), null);
        } catch (ProviderException error) {
            return new StreamResult(false, failureFrom(error, "캐릭터 음성을 준비하지 못했어요."), null);
        }
    }

    private Map<String, Object> failure(ProviderErrorCode code, String safeDetail) {
        return failureEnvelope(code.name(), "tts", code.defaultRetryable(), safeDetail);
    }

    private Map<String, Object> failureFrom(ProviderException error, String fallbackSafeDetail) {
        return failureEnvelope(error.code().name(), "tts", error.retryable(), fallbackSafeDetail);
    }

    private Map<String, Object> failureEnvelope(String code, String stage, boolean retryable, String safeDetail) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("code", code);
        failure.put("stage", stage);
        failure.put("retryable", retryable);
        failure.put("safeDetail", safeDetail);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("failure", failure);
        return result;
    }
}

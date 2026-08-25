package com.qstory.backend.question.service;

import com.qstory.backend.config.AppProperties;
import com.qstory.backend.common.error.AbortException;
import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.common.error.ProviderException;
import com.qstory.backend.provider.ProviderReadiness;
import com.qstory.backend.provider.audio.service.AudioNormalizer;
import com.qstory.backend.provider.audio.NormalizedAudio;
import com.qstory.backend.provider.openrouter.util.OpenRouterClient;
import com.qstory.backend.provider.openrouter.RouteDecision;
import com.qstory.backend.provider.openrouter.SynthesizedAudio;
import com.qstory.backend.provider.rtzr.util.RtzrSttClient;
import com.qstory.backend.provider.rtzr.RtzrTranscriptionResult;
import com.qstory.backend.question.dto.AudioPayload;
import com.qstory.backend.question.dto.FallbackPlan;
import com.qstory.backend.question.dto.RoutePlan;
import com.qstory.backend.question.dto.SpeechResult;
import com.qstory.backend.story.StoryContext;
import com.qstory.backend.story.service.StoryRegistryService.ResolvedQuestionContext;
import com.qstory.backend.voicecast.service.VoiceCastService;
import com.qstory.backend.common.util.RequestDeadline;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** question-pipeline.mjs를 Java로 이식한 것. */
@Service
public class QuestionPipelineService {

    private static final Duration ROUTE_RETRY_DELAY = Duration.ofMillis(350);

    private final AppProperties config;
    private final AudioNormalizer normalizer;
    private final RtzrSttClient sttClient;
    private final OpenRouterClient openRouterClient;
    private final VoiceCastService voiceCastService;

    public QuestionPipelineService(
            AppProperties config, AudioNormalizer normalizer, RtzrSttClient sttClient,
            OpenRouterClient openRouterClient, VoiceCastService voiceCastService) {
        this.config = config;
        this.normalizer = normalizer;
        this.sttClient = sttClient;
        this.openRouterClient = openRouterClient;
        this.voiceCastService = voiceCastService;
    }

    public Map<String, Object> transcribe(ResolvedQuestionContext context, byte[] audio, RequestDeadline deadline) {
        return transcribeRecording(context, audio, deadline, System.nanoTime());
    }

    public Map<String, Object> route(ResolvedQuestionContext context, String transcript, RequestDeadline deadline) {
        return routeTranscript(context, transcript, "ko", "text/plain", newDiagnostics(transcript), deadline, false, System.nanoTime());
    }

    public Map<String, Object> process(ResolvedQuestionContext context, byte[] audio, RequestDeadline deadline) {
        long startedAt = System.nanoTime();
        Map<String, Object> transcription = transcribeRecording(context, audio, deadline, startedAt);
        if (Boolean.FALSE.equals(transcription.get("ok"))) {
            ProviderReadiness readiness = ProviderReadiness.of(config);
            Object failure = transcription.get("failure");
            if (failure instanceof Map<?, ?> failureMap
                    && ProviderErrorCode.STT_PROVIDER_NOT_CONFIGURED.name().equals(failureMap.get("code"))
                    && (!readiness.llm() || !readiness.tts())) {
                Map<String, Object> rewritten = new LinkedHashMap<>(transcription);
                Map<String, Object> rewrittenFailure = new LinkedHashMap<>((Map<String, Object>) failureMap);
                rewrittenFailure.put("code", ProviderErrorCode.SPEECH_PROVIDER_NOT_CONFIGURED.name());
                rewrittenFailure.put("safeDetail", "실제 음성 처리 공급자가 아직 연결되지 않았어요.");
                rewritten.put("failure", rewrittenFailure);
                return rewritten;
            }
            return transcription;
        }
        SpeechResult speech = (SpeechResult) transcription.get("speech");
        return routeTranscript(
                context, speech.transcript(), speech.locale(), speech.normalizedMimeType(),
                (Map<String, Object>) transcription.get("diagnostics"), deadline, true, startedAt);
    }

    public Map<String, Object> processText(ResolvedQuestionContext context, String transcript, RequestDeadline deadline) {
        return routeTranscript(context, transcript, "ko", "text/plain", newDiagnostics(transcript), deadline, false, System.nanoTime());
    }

    private Map<String, Object> newDiagnostics(String transcript) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("converted", false);
        diagnostics.put("receivedBytes", transcript.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        diagnostics.put("normalizedBytes", transcript.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        diagnostics.put("normalizationMs", 0);
        diagnostics.put("sttMs", 0);
        return diagnostics;
    }

    private Map<String, Object> transcribeRecording(
            ResolvedQuestionContext context, byte[] audio, RequestDeadline deadline, long startedAtNanos) {
        if (!ProviderReadiness.of(config).stt()) {
            return failureEnvelope(
                    ProviderErrorCode.STT_PROVIDER_NOT_CONFIGURED, "stt", false,
                    "실제 음성 인식 공급자가 아직 연결되지 않았어요.", context.storyContext());
        }
        try {
            NormalizedAudio normalized = normalizer.normalize(audio, context.sourceMimeType(), deadline);
            long normalizedAtNanos = System.nanoTime();
            RtzrTranscriptionResult speech = sttClient.transcribe(
                    normalized.audio(), normalized.extension(), normalized.mimeType(),
                    context.storyContext().sttKeywords(), deadline);
            long transcribedAtNanos = System.nanoTime();
            if (speech.transcript() == null || speech.transcript().isEmpty()) {
                return failureEnvelope(
                        ProviderErrorCode.NO_SPEECH_DETECTED, "stt", true,
                        "이번에는 말소리를 문장으로 확인하지 못했어요.", context.storyContext());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("speech", SpeechResult.of(speech.transcript(), speech.locale(), normalized.mimeType()));
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("converted", normalized.converted());
            diagnostics.put("receivedBytes", audio.length);
            diagnostics.put("normalizedBytes", normalized.audio().length);
            diagnostics.put("normalizationMs", millisBetween(startedAtNanos, normalizedAtNanos));
            diagnostics.put("sttMs", millisBetween(normalizedAtNanos, transcribedAtNanos));
            diagnostics.put("totalMs", millisBetween(startedAtNanos, transcribedAtNanos));
            result.put("diagnostics", diagnostics);
            return result;
        } catch (Exception error) {
            return failedResult(error, context.storyContext(), "stt");
        }
    }

    private Map<String, Object> routeTranscript(
            ResolvedQuestionContext context, String transcript, String locale, String normalizedMimeType,
            Map<String, Object> diagnostics, RequestDeadline deadline, boolean includeAudio, long startedAtNanos) {
        ProviderReadiness readiness = ProviderReadiness.of(config);
        if (!readiness.llm() || (includeAudio && !readiness.tts())) {
            return failureEnvelope(
                    ProviderErrorCode.RESPONSE_PROVIDER_NOT_CONFIGURED, "response", false,
                    "질문 답변 공급자가 아직 연결되지 않았어요.", context.storyContext());
        }
        try {
            return respondToTranscript(context, transcript, locale, normalizedMimeType, includeAudio, diagnostics, deadline, startedAtNanos);
        } catch (Exception error) {
            return failedResult(error, context.storyContext(), "response");
        }
    }

    private Map<String, Object> respondToTranscript(
            ResolvedQuestionContext context, String transcript, String locale, String normalizedMimeType,
            boolean includeAudio, Map<String, Object> priorDiagnostics, RequestDeadline deadline, long startedAtNanos) {
        StoryContext storyContext = context.storyContext();
        long responseStartedAtNanos = System.nanoTime();
        OpenRouterClient.PlanRequest planRequest = new OpenRouterClient.PlanRequest(
                transcript, storyContext, context.questionRound(), context.guaranteeAgencyChoice());

        RouteDecision decision = null;
        int responseAttempts = 0;
        for (int attempt = 0; attempt < 2; attempt++) {
            responseAttempts = attempt + 1;
            try {
                decision = openRouterClient.generatePlan(planRequest, deadline);
                break;
            } catch (ProviderException error) {
                boolean shouldRetry = attempt == 0 && error.retryable();
                if (!shouldRetry) {
                    throw error;
                }
                sleep(ROUTE_RETRY_DELAY);
            }
        }
        if (decision == null) {
            throw new ProviderException(ProviderErrorCode.OPENROUTER_RESPONSE_MISSING, "질문에 대한 답을 준비하지 못했어요.");
        }
        long plannedAtNanos = System.nanoTime();
        RoutePlan plan = RoutePlan.of(decision);

        SynthesizedAudio generatedAudio = null;
        String ttsFailureCode = null;
        if (includeAudio) {
            try {
                var cast = voiceCastService.voiceCastForSpeaker(context.storyId(), decision.speakerId());
                String ttsInput = voiceCastService.buildGeminiTtsPerformanceInput(
                        context.storyId(), decision.speakerId(), decision.responseText());
                generatedAudio = openRouterClient.synthesize(ttsInput, cast.voice(), 1.0, deadline);
            } catch (ProviderException error) {
                ttsFailureCode = error.code().name();
            } catch (AbortException abort) {
                ttsFailureCode = "SPEECH_PIPELINE_TTS_TIMEOUT";
            } catch (Exception other) {
                ttsFailureCode = "SPEECH_PIPELINE_TTS_FAILED";
            }
        }
        long completedAtNanos = System.nanoTime();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("speech", SpeechResult.of(transcript, locale, normalizedMimeType));
        result.put("plan", plan);
        result.put("audioText", decision.responseText());

        Map<String, Object> diagnostics = new LinkedHashMap<>(priorDiagnostics);
        diagnostics.put("responseAttempts", responseAttempts);
        diagnostics.put("responseMs", millisBetween(responseStartedAtNanos, plannedAtNanos));
        diagnostics.put("ttsMs", millisBetween(plannedAtNanos, completedAtNanos));
        diagnostics.put("totalMs", millisBetween(startedAtNanos, completedAtNanos));
        diagnostics.put("ttsStatus", includeAudio ? (generatedAudio != null ? "generated" : "device-fallback") : "not-requested");
        diagnostics.put("ttsFailureCode", ttsFailureCode);
        result.put("diagnostics", diagnostics);

        if (generatedAudio != null) {
            result.put("audio", AudioPayload.of(generatedAudio.mimeType(), generatedAudio.audio()));
        }
        return result;
    }

    private Map<String, Object> failedResult(Exception error, StoryContext storyContext, String timeoutStage) {
        if (error instanceof AbortException) {
            return failureEnvelope(
                    ProviderErrorCode.SPEECH_PIPELINE_TIMEOUT, timeoutStage, true,
                    "답을 준비하는 시간이 길어져 이야기로 돌아갈게요.", storyContext);
        }
        if (error instanceof ProviderException providerException) {
            return failureEnvelope(
                    providerException.code().name(), providerException.stage(), providerException.retryable(),
                    providerException.safeDetail(), storyContext);
        }
        return failureEnvelope(
                ProviderErrorCode.SPEECH_PIPELINE_FAILED, "response", true, "질문을 처리하지 못했어요.", storyContext);
    }

    private Map<String, Object> failureEnvelope(
            ProviderErrorCode code, String stage, boolean retryable, String safeDetail, StoryContext storyContext) {
        return failureEnvelope(code.name(), stage, retryable, safeDetail, storyContext);
    }

    private Map<String, Object> failureEnvelope(
            String code, String stage, boolean retryable, String safeDetail, StoryContext storyContext) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("code", code);
        failure.put("stage", stage);
        failure.put("retryable", retryable);
        failure.put("safeDetail", safeDetail);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("failure", failure);
        result.put("fallback", FallbackPlan.of(storyContext));
        return result;
    }

    private static long millisBetween(long startNanos, long endNanos) {
        return Math.round((endNanos - startNanos) / 1_000_000.0);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AbortException("request-timeout");
        }
    }
}

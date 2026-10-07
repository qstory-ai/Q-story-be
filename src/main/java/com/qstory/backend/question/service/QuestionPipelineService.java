package com.qstory.backend.question.service;

import com.qstory.backend.config.AppProperties;
import com.qstory.backend.common.error.AbortException;
import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ProviderException;
import com.qstory.backend.conversationrecord.ConversationAttribution;
import com.qstory.backend.conversationrecord.service.ConversationRecordService;
import com.qstory.backend.provider.ProviderReadiness;
import com.qstory.backend.provider.audio.service.AudioNormalizer;
import com.qstory.backend.provider.audio.NormalizedAudio;
import com.qstory.backend.provider.openrouter.RouteDecision;
import com.qstory.backend.provider.rtzr.util.RtzrSttClient;
import com.qstory.backend.provider.rtzr.RtzrTranscriptionResult;
import com.qstory.backend.question.dto.FallbackPlan;
import com.qstory.backend.question.dto.RoutePlan;
import com.qstory.backend.question.dto.SpeechResult;
import com.qstory.backend.story.StoryContext;
import com.qstory.backend.story.service.StoryRegistryService.ResolvedQuestionContext;
import com.qstory.backend.common.util.RequestDeadline;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 질문 파이프라인: 오디오 정규화 → STT(transcribe), 전사문 → 라우팅(route, QuestionRoutingService). 응답 음성은 프론트가 /v1/narrations로 따로 받는다. */
@Service
public class QuestionPipelineService {

    private static final Logger log = LoggerFactory.getLogger(QuestionPipelineService.class);

    private static final Duration ROUTE_RETRY_DELAY = Duration.ofMillis(350);

    private final AppProperties config;
    private final AudioNormalizer normalizer;
    private final RtzrSttClient sttClient;
    private final QuestionRoutingService questionRoutingService;
    private final ConversationRecordService conversationRecordService;

    public QuestionPipelineService(
            AppProperties config, AudioNormalizer normalizer, RtzrSttClient sttClient,
            QuestionRoutingService questionRoutingService, ConversationRecordService conversationRecordService) {
        this.config = config;
        this.normalizer = normalizer;
        this.sttClient = sttClient;
        this.questionRoutingService = questionRoutingService;
        this.conversationRecordService = conversationRecordService;
    }

    public Map<String, Object> transcribe(
            ResolvedQuestionContext context, byte[] audio, RequestDeadline deadline, ConversationAttribution attribution) {
        return transcribeRecording(context, audio, deadline, System.nanoTime(), attribution);
    }

    public Map<String, Object> route(
            ResolvedQuestionContext context, String transcript, RequestDeadline deadline, ConversationAttribution attribution) {
        return routeTranscript(context, transcript, "ko", "text/plain", newDiagnostics(transcript), deadline, System.nanoTime(), attribution);
    }

    private Map<String, Object> newDiagnostics(String transcript) {
        int byteLength = transcript.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("converted", false);
        diagnostics.put("receivedBytes", byteLength);
        diagnostics.put("normalizedBytes", byteLength);
        diagnostics.put("normalizationMs", 0);
        diagnostics.put("sttMs", 0);
        return diagnostics;
    }

    private Map<String, Object> transcribeRecording(
            ResolvedQuestionContext context, byte[] audio, RequestDeadline deadline, long startedAtNanos,
            ConversationAttribution attribution) {
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
            // STT 결과를 원장에 남긴다 - 아이가 이 문장을 확인하고 라우팅까지 가지 않아도(취소·재녹음)
            // "무슨 말을 했는지"는 남는다. 라우팅되면 QUESTION_ROUTE 행이 따로 추가된다.
            conversationRecordService.recordQuestionTranscript(
                    context.storyId(), context.sceneId(), context.anchorId(), context.questionRound(),
                    speech.transcript(), speech.locale(), normalized.mimeType(), attribution);
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
        } catch (ApiException unavailable) {
            // STT 업체 장애(STT_UNAVAILABLE)는 200 실패 봉투가 아니라 503으로 그대로 올린다.
            throw unavailable;
        } catch (Exception error) {
            return failedResult(error, context.storyContext(), "stt");
        }
    }

    private Map<String, Object> routeTranscript(
            ResolvedQuestionContext context, String transcript, String locale, String normalizedMimeType,
            Map<String, Object> diagnostics, RequestDeadline deadline, long startedAtNanos,
            ConversationAttribution attribution) {
        if (!ProviderReadiness.of(config).llm()) {
            return failureEnvelope(
                    ProviderErrorCode.RESPONSE_PROVIDER_NOT_CONFIGURED, "response", false,
                    "질문 답변 공급자가 아직 연결되지 않았어요.", context.storyContext());
        }
        try {
            return respondToTranscript(context, transcript, locale, normalizedMimeType, diagnostics, deadline, startedAtNanos, attribution);
        } catch (Exception error) {
            return failedResult(error, context.storyContext(), "response");
        }
    }

    private Map<String, Object> respondToTranscript(
            ResolvedQuestionContext context, String transcript, String locale, String normalizedMimeType,
            Map<String, Object> priorDiagnostics, RequestDeadline deadline, long startedAtNanos,
            ConversationAttribution attribution) {
        StoryContext storyContext = context.storyContext();
        long responseStartedAtNanos = System.nanoTime();

        RouteDecision decision = null;
        int responseAttempts = 0;
        for (int attempt = 0; attempt < 2; attempt++) {
            responseAttempts = attempt + 1;
            try {
                decision = questionRoutingService.route(
                        storyContext, transcript, context.questionRound(), context.guaranteeAgencyChoice(), deadline);
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
        conversationRecordService.recordQuestionRoute(
                context.storyId(), context.sceneId(), context.anchorId(), context.questionRound(),
                transcript, locale, normalizedMimeType, decision, attribution);

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
        // 이 경로는 음성을 합성하지 않는다 - 응답 형태를 유지하려고 TTS 진단 값을 고정으로 둔다.
        diagnostics.put("ttsStatus", "not-requested");
        diagnostics.put("ttsFailureCode", null);
        result.put("diagnostics", diagnostics);
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
        // 예상 밖 예외는 원인을 WARN으로 남기고, 사용자에게는 안전한 안내 대사만 보여준다.
        log.warn("question-pipeline.unexpected-failure storyId={} anchorId={} stage={} type={}",
                storyContext.storyId(), storyContext.anchorId(), timeoutStage,
                error.getClass().getName(), error);
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

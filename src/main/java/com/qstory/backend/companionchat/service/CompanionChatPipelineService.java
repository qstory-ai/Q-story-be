package com.qstory.backend.companionchat.service;

import com.qstory.backend.common.error.AbortException;
import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.common.error.ProviderException;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.companionchat.entity.CompanionChatTurn;
import com.qstory.backend.companionchat.repository.CompanionChatTurnRepository;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.provider.ProviderReadiness;
import com.qstory.backend.provider.audio.NormalizedAudio;
import com.qstory.backend.provider.audio.service.AudioNormalizer;
import com.qstory.backend.provider.openrouter.SynthesizedAudio;
import com.qstory.backend.provider.openrouter.util.OpenRouterClient;
import com.qstory.backend.provider.rtzr.RtzrTranscriptionResult;
import com.qstory.backend.provider.rtzr.util.RtzrSttClient;
import com.qstory.backend.question.dto.AudioPayload;
import com.qstory.backend.story.service.StoryRegistryService.ResolvedCompanionContext;
import com.qstory.backend.voicecast.service.VoiceCastService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 구조적으로는 QuestionPipelineService.respondToTranscript와 쌍둥이 - LLM 호출 한 번, TTS 호출
 * 한 번 - 이지만 앵커와 무관하며 anchors/families/options는 절대 반환하지 않고 응답과 그
 * 오디오만 반환한다.
 */
@Service
public class CompanionChatPipelineService {

    private final AppProperties config;
    private final AudioNormalizer normalizer;
    private final RtzrSttClient sttClient;
    private final OpenRouterClient openRouterClient;
    private final VoiceCastService voiceCastService;
    private final CompanionChatTurnRepository turnRepository;

    public CompanionChatPipelineService(
            AppProperties config, AudioNormalizer normalizer, RtzrSttClient sttClient,
            OpenRouterClient openRouterClient, VoiceCastService voiceCastService,
            CompanionChatTurnRepository turnRepository) {
        this.config = config;
        this.normalizer = normalizer;
        this.sttClient = sttClient;
        this.openRouterClient = openRouterClient;
        this.voiceCastService = voiceCastService;
        this.turnRepository = turnRepository;
    }

    /**
     * QuestionPipelineService.transcribeRecording과 같은 3단계(정규화 → STT → 빈 transcript 체크)를
     * 따르지만, 앵커 기반 FallbackPlan은 만들지 않는다 - 컴패니언 챗에는 애초에 분기/패밀리 개념이
     * 없기 때문에(클래스 상단 주석 참고) 실패 시에도 이 클래스의 단순 failureEnvelope만 반환한다.
     */
    public Map<String, Object> transcribe(
            ResolvedCompanionContext context, String sourceMimeType, byte[] audio, RequestDeadline deadline) {
        if (!ProviderReadiness.of(config).stt()) {
            return failureEnvelope(
                    ProviderErrorCode.STT_PROVIDER_NOT_CONFIGURED, "stt", false,
                    "실제 음성 인식 공급자가 아직 연결되지 않았어요.");
        }
        try {
            NormalizedAudio normalized = normalizer.normalize(audio, sourceMimeType, deadline);
            RtzrTranscriptionResult speech = sttClient.transcribe(
                    normalized.audio(), normalized.extension(), normalized.mimeType(),
                    context.sttKeywords(), deadline);
            if (speech.transcript() == null || speech.transcript().isEmpty()) {
                return failureEnvelope(
                        ProviderErrorCode.NO_SPEECH_DETECTED, "stt", true,
                        "이번에는 말소리를 문장으로 확인하지 못했어요.");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("transcript", speech.transcript());
            return result;
        } catch (AbortException abort) {
            return failureEnvelope(
                    ProviderErrorCode.SPEECH_PIPELINE_TIMEOUT, "stt", true,
                    "목소리를 인식하는 시간이 길어졌어요. 잠시 후 다시 말해 주세요.");
        } catch (ProviderException providerException) {
            return failureEnvelope(
                    providerException.code(), providerException.stage(), providerException.retryable(),
                    providerException.safeDetail());
        } catch (Exception other) {
            return failureEnvelope(
                    ProviderErrorCode.SPEECH_PIPELINE_FAILED, "stt", true, "지금은 목소리를 인식하지 못했어요.");
        }
    }

    public Map<String, Object> respond(
            ResolvedCompanionContext context, UUID conversationId, String transcript, RequestDeadline deadline) {
        ProviderReadiness readiness = ProviderReadiness.of(config);
        if (!readiness.llm() || !readiness.tts()) {
            return failureEnvelope(
                    ProviderErrorCode.RESPONSE_PROVIDER_NOT_CONFIGURED, "response", false,
                    "대화 공급자가 아직 연결되지 않았어요.");
        }
        try {
            return respondToTranscript(context, conversationId, transcript, deadline);
        } catch (Exception error) {
            return failedResult(error);
        }
    }

    private Map<String, Object> respondToTranscript(
            ResolvedCompanionContext context, UUID conversationId, String transcript, RequestDeadline deadline) {
        OpenRouterClient.CompanionRequest request = new OpenRouterClient.CompanionRequest(
                transcript, context.versions().promptVersion(), context.primarySpeakerId(),
                context.allowedSpeakerIds(), context.forbiddenKnowledge());
        OpenRouterClient.CompanionReply reply = openRouterClient.generateCompanionReply(request, deadline);

        turnRepository.save(CompanionChatTurn.builder()
                .id(UUID.randomUUID())
                .conversationId(conversationId)
                .storyId(context.story().storyId())
                .sceneId(context.sceneId())
                .occurredAt(Instant.now())
                .interactionMode(com.qstory.backend.common.enums.CompanionInteractionMode.valueOf(reply.interactionMode()))
                .topicTag(reply.topicTag())
                .toneTag(reply.toneTag())
                .valueTag(reply.valueTag())
                .build());

        SynthesizedAudio generatedAudio = null;
        String ttsFailureCode = null;
        try {
            var cast = voiceCastService.voiceCastForSpeaker(context.story().storyId(), reply.speakerId());
            String ttsInput = voiceCastService.buildGeminiTtsPerformanceInput(
                    context.story().storyId(), reply.speakerId(), reply.responseText());
            generatedAudio = openRouterClient.synthesize(ttsInput, cast.voice(), 1.0, deadline);
        } catch (ProviderException error) {
            ttsFailureCode = error.code().name();
        } catch (AbortException abort) {
            ttsFailureCode = "SPEECH_PIPELINE_TTS_TIMEOUT";
        } catch (Exception other) {
            ttsFailureCode = "SPEECH_PIPELINE_TTS_FAILED";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("responseText", reply.responseText());
        result.put("safety", Map.of("mode", reply.interactionMode()));
        if (generatedAudio != null) {
            result.put("audio", AudioPayload.of(generatedAudio.mimeType(), generatedAudio.audio()));
        }
        if (ttsFailureCode != null) {
            result.put("ttsFailureCode", ttsFailureCode);
        }
        return result;
    }

    private Map<String, Object> failedResult(Exception error) {
        if (error instanceof AbortException) {
            return failureEnvelope(
                    ProviderErrorCode.SPEECH_PIPELINE_TIMEOUT, "response", true,
                    "답을 준비하는 시간이 길어졌어요. 잠시 후 다시 물어봐 주세요.");
        }
        if (error instanceof ProviderException providerException) {
            return failureEnvelope(
                    providerException.code(), providerException.stage(), providerException.retryable(),
                    providerException.safeDetail());
        }
        return failureEnvelope(
                ProviderErrorCode.SPEECH_PIPELINE_FAILED, "response", true, "지금은 대답을 준비하지 못했어요.");
    }

    private Map<String, Object> failureEnvelope(ProviderErrorCode code, String stage, boolean retryable, String safeDetail) {
        return failureEnvelope(code.name(), stage, retryable, safeDetail);
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

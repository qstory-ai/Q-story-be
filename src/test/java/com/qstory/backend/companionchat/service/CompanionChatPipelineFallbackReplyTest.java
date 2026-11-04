package com.qstory.backend.companionchat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.common.error.ProviderException;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.companionchat.DialogueInput;
import com.qstory.backend.companionchat.repository.CompanionChatTurnRepository;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.config.DialogueProperties;
import com.qstory.backend.conversationrecord.ConversationAttribution;
import com.qstory.backend.conversationrecord.service.ConversationRecordService;
import com.qstory.backend.provider.audio.service.AudioNormalizer;
import com.qstory.backend.provider.gemini.util.GeminiTtsClient;
import com.qstory.backend.provider.openrouter.util.OpenRouterClient;
import com.qstory.backend.provider.rtzr.util.RtzrSttClient;
import com.qstory.backend.story.StoryManifest;
import com.qstory.backend.story.StoryVersions;
import com.qstory.backend.story.service.StoryRegistryService.ResolvedCompanionContext;
import com.qstory.backend.voicecast.service.VoiceCastService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** LLM이 답을 못 만들면 오류 봉투 대신 그레텔의 준비된 한 줄을 평범한 답처럼 돌려준다. */
class CompanionChatPipelineFallbackReplyTest {

    private final AppProperties config = mock(AppProperties.class, RETURNS_DEEP_STUBS);
    private final OpenRouterClient openRouter = mock(OpenRouterClient.class);
    private final GeminiTtsClient tts = mock(GeminiTtsClient.class);
    private final ConversationRecordService records = mock(ConversationRecordService.class);
    private final CompanionChatTurnRepository turns = mock(CompanionChatTurnRepository.class);
    private final CompanionChatPipelineService pipeline = new CompanionChatPipelineService(
            config, mock(AudioNormalizer.class), mock(RtzrSttClient.class), openRouter, tts,
            mock(VoiceCastService.class), turns, records, new DialogueProperties(false), null, null);

    @BeforeEach
    void providersConfigured() {
        when(config.providers().openRouter().llmConfigured()).thenReturn(true);
        when(config.providers().gemini().ttsConfigured()).thenReturn(true);
    }

    private ResolvedCompanionContext context() {
        StoryManifest story = new StoryManifest(
                "HG", "hansel-gretel", "헨젤과 그레텔", "v1", "PUBLIC", "QSTORY_ROUTE_PROMPT_V7_COVERAGE", "p1", "n1",
                Map.of(), "c1", Map.of(), false, null, null, null);
        return new ResolvedCompanionContext(
                story, "HG-F05", "HG-SPK-GRETEL", List.of("HG-SPK-GRETEL"), List.of(), List.of(),
                new StoryVersions("QSTORY_ROUTE_PROMPT_V7_COVERAGE", "p1", "v1", "n1"), null);
    }

    @Test
    void llmFailureBecomesAPreparedLineInsteadOfAFailureEnvelope() {
        when(openRouter.generateCompanionReply(any(), any()))
                .thenThrow(new ProviderException(ProviderErrorCode.SPEECH_PIPELINE_FAILED, "키가 거절됐어요.", true));

        Map<String, Object> result = pipeline.respond(
                context(), UUID.randomUUID(), "저 노파는 누구야?", DialogueInput.empty(), null, true,
                RequestDeadline.startingNow(30_000), mock(ConversationAttribution.class));

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("responseText")).isEqualTo(CompanionChatPipelineService.FALLBACK_REPLY_TEXT);
        assertThat(result.get("audioDeferred")).isEqualTo(true);
        assertThat(result.get("speakerId")).isEqualTo("HG-SPK-GRETEL");
        @SuppressWarnings("unchecked")
        Map<String, Object> dialogue = (Map<String, Object>) result.get("dialogue");
        assertThat(dialogue.get("replyKind")).isEqualTo("ANSWER");
        assertThat(dialogue.get("childWantsToEnd")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> fallback = (Map<String, Object>) result.get("fallback");
        assertThat(fallback.get("code")).isEqualTo("SPEECH_PIPELINE_FAILED");
        assertThat(fallback.get("stage")).isEqualTo("response");
        assertThat(result).doesNotContainKeys("failure", "audio");
        // 준비된 한 줄은 LLM 답이 아니다 - 태그 행과 대화 원장에 남기지 않고, 음성도 여기서 만들지 않는다.
        verify(turns, never()).save(any());
        verify(records, never()).recordCompanionTurn(any(), any(), any(), any(), any(), any(), any(), any());
        verify(tts, never()).synthesize(anyString(), anyString(), anyDouble(), any());
    }

    @Test
    void unexpectedExceptionAlsoFallsBackAndCarriesTheGenericCode() {
        when(openRouter.generateCompanionReply(any(), any())).thenThrow(new IllegalStateException("boom"));

        Map<String, Object> result = pipeline.respond(
                context(), UUID.randomUUID(), "왜?", DialogueInput.empty(), null, false,
                RequestDeadline.startingNow(30_000), mock(ConversationAttribution.class));

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("responseText")).isEqualTo(CompanionChatPipelineService.FALLBACK_REPLY_TEXT);
        @SuppressWarnings("unchecked")
        Map<String, Object> fallback = (Map<String, Object>) result.get("fallback");
        assertThat(fallback.get("code")).isEqualTo("SPEECH_PIPELINE_FAILED");
        // 예전 클라이언트(deferAudio 없음)에는 음성 없이 글만 간다.
        assertThat(result).doesNotContainKeys("audioDeferred", "audio");
    }

    @Test
    void helpRequestKeepsTheFailureEnvelopeSoTheClientUsesItsRecordedHelpLine() {
        when(openRouter.generateCompanionReply(any(), any())).thenThrow(new IllegalStateException("boom"));
        DialogueInput help = new DialogueInput(
                List.of(), null, List.of(), "HG-Q-A", "NONE", new DialogueInput.Help(1, 3, "하얀 새를 잘 봐."),
                DialogueInput.Attempt.NONE);

        Map<String, Object> result = pipeline.respond(
                context(), UUID.randomUUID(), "도와줘", help, null, true,
                RequestDeadline.startingNow(30_000), mock(ConversationAttribution.class));

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result).containsKey("failure");
    }

    @Test
    void failureAfterTheStreamedSentenceWentOutKeepsTheFailureEnvelope() {
        when(openRouter.streamCompanionReply(any(), any(), any())).thenAnswer(invocation -> {
            Consumer<String> onText = invocation.getArgument(2);
            onText.accept("처음 보는 할머니야.");
            throw new IllegalStateException("tags failed");
        });
        StringBuilder streamed = new StringBuilder();

        Map<String, Object> result = pipeline.respond(
                context(), UUID.randomUUID(), "저 노파는 누구야?", DialogueInput.empty(), null, true,
                RequestDeadline.startingNow(30_000), mock(ConversationAttribution.class), streamed::append);

        // 프런트는 이미 보인 문장으로 마무리한다 - 대체 답을 또 보내면 말풍선이 둘이 된다.
        assertThat(streamed.toString()).isEqualTo("처음 보는 할머니야.");
        assertThat(result.get("ok")).isEqualTo(false);
    }

    @Test
    void streamedFailureBeforeAnySentenceStillFallsBack() {
        when(openRouter.streamCompanionReply(any(), any(), any())).thenThrow(new IllegalStateException("down"));

        Map<String, Object> result = pipeline.respond(
                context(), UUID.randomUUID(), "저 노파는 누구야?", DialogueInput.empty(), null, true,
                RequestDeadline.startingNow(30_000), mock(ConversationAttribution.class), text -> {});

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("responseText")).isEqualTo(CompanionChatPipelineService.FALLBACK_REPLY_TEXT);
    }
}

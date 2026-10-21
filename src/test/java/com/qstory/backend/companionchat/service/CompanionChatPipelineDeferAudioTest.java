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
import org.junit.jupiter.api.Test;

/** 말풍선을 음성보다 먼저 - deferAudio면 TTS를 부르지 않고 글 답만 바로 돌려준다. */
class CompanionChatPipelineDeferAudioTest {

    private final AppProperties config = mock(AppProperties.class, RETURNS_DEEP_STUBS);
    private final OpenRouterClient openRouter = mock(OpenRouterClient.class);
    private final GeminiTtsClient tts = mock(GeminiTtsClient.class);
    private final ConversationRecordService records = mock(ConversationRecordService.class);
    private final CompanionChatPipelineService pipeline = new CompanionChatPipelineService(
            config, mock(AudioNormalizer.class), mock(RtzrSttClient.class), openRouter, tts,
            mock(VoiceCastService.class), mock(CompanionChatTurnRepository.class), records, new DialogueProperties(false), null, null);

    private ResolvedCompanionContext context() {
        StoryManifest story = new StoryManifest(
                "HG", "hansel-gretel", "헨젤과 그레텔", "v1", "PUBLIC", "QSTORY_ROUTE_PROMPT_V7_COVERAGE", "p1", "n1",
                Map.of(), "c1", Map.of(), false, null, null, null);
        return new ResolvedCompanionContext(
                story, "HG-F05", "HG-SPK-GRETEL", List.of("HG-SPK-GRETEL"), List.of(), List.of(),
                new StoryVersions("QSTORY_ROUTE_PROMPT_V7_COVERAGE", "p1", "v1", "n1"), null);
    }

    @Test
    void deferredReplyComesBackWithoutCallingTts() {
        when(config.providers().openRouter().llmConfigured()).thenReturn(true);
        when(config.providers().gemini().ttsConfigured()).thenReturn(true);
        when(openRouter.generateCompanionReply(any(), any())).thenReturn(new OpenRouterClient.CompanionReply(
                "ANSWER", "처음 보는 할머니야. 너는 어떤 분 같아?", "HG-SPK-GRETEL", null, null, null,
                "ANSWER", false, "누구인지 궁금함", false, null));

        Map<String, Object> result = pipeline.respond(
                context(), UUID.randomUUID(), "저 노파는 누구야?", DialogueInput.empty(), null, true,
                RequestDeadline.startingNow(30_000), mock(ConversationAttribution.class));

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("responseText")).isEqualTo("처음 보는 할머니야. 너는 어떤 분 같아?");
        assertThat(result.get("audioDeferred")).isEqualTo(true);
        assertThat(result).doesNotContainKeys("audio", "ttsFailureCode");
        verify(tts, never()).synthesize(anyString(), anyString(), anyDouble(), any());
        // 대화 원장에는 라우팅 프롬프트와 그레텔 대화 프롬프트 버전이 함께 남는다(실패해 보기 스위치를 끄면 V2).
        verify(records).recordCompanionTurn(
                any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq("QSTORY_ROUTE_PROMPT_V7_COVERAGE+" + OpenRouterClient.COMPANION_PROMPT_VERSION),
                any(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void attemptSceneSettingsReachTheRequestAndTheSceneComesBack() {
        CompanionChatPipelineService withAttempts = new CompanionChatPipelineService(
                config, mock(AudioNormalizer.class), mock(RtzrSttClient.class), openRouter, tts,
                mock(VoiceCastService.class), mock(CompanionChatTurnRepository.class), records,
                new DialogueProperties(null, null, 3), null, null);
        when(config.providers().openRouter().llmConfigured()).thenReturn(true);
        when(config.providers().gemini().ttsConfigured()).thenReturn(true);
        when(openRouter.generateCompanionReply(any(), any())).thenReturn(new OpenRouterClient.CompanionReply(
                "ANSWER", "창문이 너무 높아… 뭘 밟고 올라갈까?", "HG-SPK-GRETEL",
                null, null, null, OpenRouterClient.TRY_FAIL, false, "창문으로 나가고 싶음", false, null,
                List.of(new OpenRouterClient.SceneLine("HG-SPK-NARRATOR", "그레텔은 창문 아래로 가 보았어요.")),
                "c-try-high-window"));
        org.mockito.ArgumentCaptor<OpenRouterClient.CompanionRequest> sent =
                org.mockito.ArgumentCaptor.forClass(OpenRouterClient.CompanionRequest.class);

        Map<String, Object> result = withAttempts.respond(
                contextWithNarrator(), UUID.randomUUID(), "창문으로 도망가자", DialogueInput.empty(), null, true,
                RequestDeadline.startingNow(30_000), mock(ConversationAttribution.class));

        verify(openRouter).generateCompanionReply(sent.capture(), any());
        // 값이 없으면 켜진 것으로 본다(QSTORY_TRY_AND_FAIL_ENABLED 기본 true), 질문 지점당 1회, 장면 안 말은 설정값.
        assertThat(sent.getValue().attemptPolicy())
                .isEqualTo(new OpenRouterClient.AttemptPolicy(true, 1, 3, "HG-SPK-NARRATOR"));
        @SuppressWarnings("unchecked")
        Map<String, Object> dialogue = (Map<String, Object>) result.get("dialogue");
        assertThat(dialogue.get("replyKind")).isEqualTo("TRY_FAIL");
        @SuppressWarnings("unchecked")
        Map<String, Object> attempt = (Map<String, Object>) dialogue.get("attempt");
        assertThat(attempt.get("maxTurns")).isEqualTo(3);
        assertThat((List<?>) attempt.get("scene")).hasSize(1);
        // 막힌 줄에서 보여 줄 배경 그림 - 예전 앱은 모르는 필드라 무시한다.
        assertThat(attempt.get("backdropAssetId")).isEqualTo("c-try-high-window");
        verify(records).recordCompanionTurn(
                any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq("QSTORY_ROUTE_PROMPT_V7_COVERAGE+" + OpenRouterClient.TRY_AND_FAIL_PROMPT_VERSION),
                any(), org.mockito.ArgumentMatchers.isNull());
    }

    private ResolvedCompanionContext contextWithNarrator() {
        StoryManifest story = new StoryManifest(
                "HG", "hansel-gretel", "헨젤과 그레텔", "v1", "PUBLIC", "QSTORY_ROUTE_PROMPT_V7_COVERAGE", "p1", "n1",
                Map.of(), "c1", Map.of("NARRATOR", new com.qstory.backend.story.CastEntry(
                        "HG-SPK-NARRATOR", "narrator", "이야기꾼 Q", "Sulafat", "p", "d", null)),
                false, null, null, null);
        return new ResolvedCompanionContext(
                story, "HG-F05", "HG-SPK-GRETEL", List.of("HG-SPK-GRETEL"), List.of(), List.of(),
                new StoryVersions("QSTORY_ROUTE_PROMPT_V7_COVERAGE", "p1", "v1", "n1"), null);
    }
}

package com.qstory.backend.livebranch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.choicecopy.service.ChoiceCopyRegistry;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.storage.SupabaseStorageClient;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.familydraft.util.FamilyDraftHarness;
import com.qstory.backend.livebranch.LiveBranchJobStatus;
import com.qstory.backend.livebranch.entity.LiveBranchJob;
import com.qstory.backend.livebranch.repository.LiveBranchJobRepository;
import com.qstory.backend.provider.openrouter.util.OpenRouterClient;
import com.qstory.backend.realtimeimage.service.RealtimeImageService;
import com.qstory.backend.story.StoryManifest;
import com.qstory.backend.story.entity.StoryAnchor;
import com.qstory.backend.story.repository.StoryActionFamilyRepository;
import com.qstory.backend.story.repository.StoryAnchorRepository;
import com.qstory.backend.story.repository.StoryAssetRepository;
import com.qstory.backend.story.repository.StoryFallbackSegmentRepository;
import com.qstory.backend.story.repository.StoryRepository;
import com.qstory.backend.story.repository.StoryVisualReferencePackRepository;
import com.qstory.backend.story.service.RoutePromptService;
import com.qstory.backend.story.service.StoryContentAssemblyService;
import com.qstory.backend.story.service.StoryRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class LiveBranchExecutionWorkerTest {

    private static final String STORY_ID = "HANSEL_GRETEL";
    private static final String PROMPT_VERSION = "QSTORY_ROUTE_PROMPT_V7_COVERAGE";
    private static final String SAFETY_FRAGMENT = "공포·폭력·위험 행동을 구체적으로 설명하지 않는다.";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final LiveBranchJobRepository jobRepository = mock(LiveBranchJobRepository.class);
    private final StoryAnchorRepository anchorRepository = mock(StoryAnchorRepository.class);
    private final StoryActionFamilyRepository familyRepository = mock(StoryActionFamilyRepository.class);
    private final OpenRouterClient openRouterClient = mock(OpenRouterClient.class);
    private final StoryRegistry storyRegistry = mock(StoryRegistry.class);
    private final StoryContentAssemblyService assemblyService = mock(StoryContentAssemblyService.class);
    private final RoutePromptService routePromptService = mock(RoutePromptService.class);

    private final LiveBranchExecutionWorker worker = new LiveBranchExecutionWorker(
            jobRepository, mock(StoryRepository.class), anchorRepository, familyRepository,
            mock(StoryFallbackSegmentRepository.class), mock(StoryAssetRepository.class),
            mock(StoryVisualReferencePackRepository.class), openRouterClient, mock(SupabaseStorageClient.class),
            objectMapper, mock(AppProperties.class), storyRegistry, mock(ChoiceCopyRegistry.class), assemblyService,
            Runnable::run, mock(FamilyDraftHarness.class), mock(PlatformTransactionManager.class),
            mock(RealtimeImageService.class), routePromptService);

    @Test
    void knownAnchorIdsReadsEveryAnchorSegmentOfTheAssembledStory() throws Exception {
        var assembled = objectMapper.readTree("""
                {"generatedContent": {"scenes": [
                  {"id": "HG-F04", "anchors": ["HG-F04-CANDY-HOUSE-REVEAL"]},
                  {"id": "HG-F05", "anchors": []},
                  {"id": "HG-F07", "anchors": ["HG-F07-KEYS-TAKEN", ""]}
                ]}}
                """);
        assertThat(LiveBranchExecutionWorker.knownAnchorIds(assembled))
                .containsExactly("HG-F04-CANDY-HOUSE-REVEAL", "HG-F07-KEYS-TAKEN");
        // 옛 이름(HG-F07-DEMONSTRATION)은 후보에서 걸러져야 한다.
        assertThat(LiveBranchExecutionWorker.knownAnchorIds(assembled)).doesNotContain("HG-F07-DEMONSTRATION");
    }

    @Test
    void knownAnchorIdsIsEmptyWhenNothingIsAssembled() {
        assertThat(LiveBranchExecutionWorker.knownAnchorIds(null)).isEmpty();
    }

    @Test
    void draftPromptKeepsLiveBranchRulesAndAddsSharedSafetyFragment() {
        String prompt = LiveBranchExecutionWorker.draftSystemPrompt(SAFETY_FRAGMENT);

        // 새 선택지 고유 규칙은 그대로 남는다.
        assertThat(prompt).contains("새 증거를 즉석에서 추가하지 마라");
        assertThat(prompt).contains("아동이 따라 할 수 있는 구체적 폭력·상해·위험 도구 사용을 쓰지 마라.");
        // 그레텔과 같은 안전 규칙 원문이 붙는다.
        assertThat(prompt).endsWith("\n" + SAFETY_FRAGMENT);
    }

    @Test
    void reviewPromptJudgesDraftAgainstSharedSafetyFragment() {
        String prompt = LiveBranchExecutionWorker.reviewSystemPrompt(SAFETY_FRAGMENT);

        assertThat(prompt).contains("달콤한 냄새·조용한 소리·친절한 말만으로 안전을 결론내면 탈락이다.");
        assertThat(prompt).contains("그레텔 대화와 같은 기준");
        assertThat(prompt).endsWith("\n" + SAFETY_FRAGMENT);
    }

    @Test
    void blankSafetyFragmentIsOmittedLikeGretel() {
        assertThat(LiveBranchExecutionWorker.draftSystemPrompt(" "))
                .endsWith("아이가 고른 행동과 그 결과를 부모에게 요약하라.");
        assertThat(LiveBranchExecutionWorker.reviewSystemPrompt(null))
                .doesNotContain("그레텔 대화와 같은 기준")
                .endsWith("모든 점수가 2일 때만 pass=true로 하라.");
    }

    @Test
    void sharedSafetyFragmentUsesTheStoryRoutePromptVersion() {
        when(storyRegistry.get(STORY_ID)).thenReturn(manifest());
        when(routePromptService.requireCompanionSafetyFragment(PROMPT_VERSION)).thenReturn(SAFETY_FRAGMENT);

        assertThat(worker.sharedSafetyFragment(STORY_ID)).isEqualTo(SAFETY_FRAGMENT);
    }

    @Test
    void sharedSafetyFragmentFailsLikeGretelWhenPromptIsMissing() {
        when(storyRegistry.get(STORY_ID)).thenReturn(manifest());
        when(routePromptService.requireCompanionSafetyFragment(PROMPT_VERSION)).thenThrow(missingPrompt());

        assertThatThrownBy(() -> worker.sharedSafetyFragment(STORY_ID)).isInstanceOf(ApiException.class);
    }

    @Test
    void jobFailsWithoutDraftingWhenSafetyFragmentCannotBeLoaded() {
        UUID jobId = UUID.randomUUID();
        LiveBranchJob job = LiveBranchJob.builder()
                .id(jobId).storyId(STORY_ID).anchorId("HG-Q-A").childTranscriptRedacted("왜?")
                .status(LiveBranchJobStatus.QUEUED).build();
        StoryAnchor anchor = StoryAnchor.builder()
                .id("HG-Q-A").slot("Q-A").summary("장면").defaultRejoinAt("HG-F04-CANDY-HOUSE-REVEAL")
                .allowedSpeakerIds(List.of("HG-SPK-GRETEL")).build();
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(anchorRepository.findById("HG-Q-A")).thenReturn(Optional.of(anchor));
        when(familyRepository.findByAnchor_IdOrderByDisplayOrderAsc("HG-Q-A")).thenReturn(List.of());
        when(familyRepository.findDistinctRejoinTargetsByAnchorId("HG-Q-A")).thenReturn(List.of());
        when(assemblyService.get(STORY_ID)).thenReturn(objectMapper.valueToTree(Map.of(
                "generatedContent", Map.of("scenes", List.of(
                        Map.of("id", "HG-F04", "anchors", List.of("HG-F04-CANDY-HOUSE-REVEAL")))))));
        when(storyRegistry.get(STORY_ID)).thenReturn(manifest());
        when(routePromptService.requireCompanionSafetyFragment(PROMPT_VERSION)).thenThrow(missingPrompt());

        worker.run(jobId);

        // 예외가 워커 밖으로 새지 않고 job만 실패 처리된다 - 안전 규칙 없는 초안 요청은 나가지 않는다.
        assertThat(job.getStatus()).isEqualTo(LiveBranchJobStatus.FAILED);
        assertThat(job.getErrorCode()).isEqualTo("GENERATION_FAILED");
        verify(openRouterClient, never()).generateStructuredCompletion(
                anyString(), anyString(), any(), anyString(), anyInt(), any(), anyString(), any());
    }

    private static ApiException missingPrompt() {
        return ApiException.contractError(ErrorCode.INTERNAL_ERROR, "질문을 처리할 준비가 아직 끝나지 않았어요.", 500);
    }

    private static StoryManifest manifest() {
        return new StoryManifest(
                STORY_ID, "hansel-gretel", "헨젤과 그레텔", "content-v1", "PUBLIC", PROMPT_VERSION,
                "policy-v1", "norm-v1", Map.of(), "cast-v1", Map.of(), false, null, null, null);
    }

    @Test
    void runRecordsThePromptVersionOnTheJobEvenWhenItFailsEarly() {
        UUID jobId = UUID.randomUUID();
        LiveBranchJob job = LiveBranchJob.builder()
                .id(jobId).storyId(STORY_ID).anchorId("missing-anchor").status(LiveBranchJobStatus.QUEUED).build();
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(anchorRepository.findById("missing-anchor")).thenReturn(Optional.empty());

        worker.run(jobId);

        assertThat(job.getPromptVersion()).isEqualTo(LiveBranchExecutionWorker.PROMPT_VERSION).isEqualTo("LIVE_BRANCH_V1");
        assertThat(job.getStatus()).isEqualTo(LiveBranchJobStatus.FAILED);
        assertThat(job.getErrorCode()).isEqualTo("ANCHOR_MISSING");
    }
}

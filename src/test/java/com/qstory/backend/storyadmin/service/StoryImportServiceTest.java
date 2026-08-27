package com.qstory.backend.storyadmin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.choicecopy.service.ChoiceCopyRegistry;
import com.qstory.backend.common.enums.FamilyOrigin;
import com.qstory.backend.story.entity.Story;
import com.qstory.backend.story.entity.StoryAnchor;
import com.qstory.backend.story.entity.StoryAsset;
import com.qstory.backend.story.entity.StoryActionFamily;
import com.qstory.backend.story.repository.RoutePromptRepository;
import com.qstory.backend.story.repository.RoutePromptStageRepository;
import com.qstory.backend.story.repository.StoryActionFamilyRepository;
import com.qstory.backend.story.repository.StoryAnchorRepository;
import com.qstory.backend.story.repository.StoryAssetRepository;
import com.qstory.backend.story.repository.StoryCastRepository;
import com.qstory.backend.story.repository.StoryFallbackSegmentRepository;
import com.qstory.backend.story.repository.StoryRepository;
import com.qstory.backend.story.repository.StorySceneRepository;
import com.qstory.backend.story.repository.StorySegmentRepository;
import com.qstory.backend.story.repository.StoryVisualReferencePackRepository;
import com.qstory.backend.story.service.RoutePromptService;
import com.qstory.backend.story.service.StoryContentAssemblyService;
import com.qstory.backend.story.service.StoryRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 이 리포지토리에는 아직 이렇다 할 테스트 인프라(임베디드/컨테이너 Postgres)가 없어서, 이 테스트는
 * 모든 리포지토리를 Mockito로 대체한 단위 테스트다 - 재임포트 시 LIVE_GENERATED family/asset이
 * 절대 삭제 대상에 포함되지 않는지, 그리고 anchor가 더 이상 delete-cascade되지 않는지를 검증한다
 * (StoryImportService.importStory()의 origin 스코핑 변경, Live Branch 기능 참고).
 */
class StoryImportServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StoryRepository storyRepository = mock(StoryRepository.class);
    private final StoryAnchorRepository anchorRepository = mock(StoryAnchorRepository.class);
    private final StoryActionFamilyRepository familyRepository = mock(StoryActionFamilyRepository.class);
    private final StoryCastRepository castRepository = mock(StoryCastRepository.class);
    private final StoryAssetRepository assetRepository = mock(StoryAssetRepository.class);
    private final RoutePromptRepository routePromptRepository = mock(RoutePromptRepository.class);
    private final RoutePromptStageRepository routePromptStageRepository = mock(RoutePromptStageRepository.class);
    private final StoryVisualReferencePackRepository visualReferencePackRepository =
            mock(StoryVisualReferencePackRepository.class);
    private final RoutePromptService routePromptService = mock(RoutePromptService.class);
    private final StoryRevisionService revisionService = mock(StoryRevisionService.class);
    private final StorySceneRepository sceneRepository = mock(StorySceneRepository.class);
    private final StorySegmentRepository segmentRepository = mock(StorySegmentRepository.class);
    private final StoryFallbackSegmentRepository fallbackSegmentRepository = mock(StoryFallbackSegmentRepository.class);
    private final StoryRegistry storyRegistry = mock(StoryRegistry.class);
    private final ChoiceCopyRegistry choiceCopyRegistry = mock(ChoiceCopyRegistry.class);
    private final StoryContentAssemblyService assemblyService = mock(StoryContentAssemblyService.class);

    private final StoryImportService service = new StoryImportService(
            objectMapper, storyRepository, anchorRepository, familyRepository, castRepository, assetRepository,
            routePromptRepository, routePromptStageRepository, visualReferencePackRepository, routePromptService,
            revisionService, sceneRepository, segmentRepository,
            fallbackSegmentRepository, storyRegistry, choiceCopyRegistry, assemblyService);

    @Test
    void reimportPreservesLiveGeneratedFamilyAndItsAsset() throws Exception {
        String storyId = "HG";
        String authoredAnchorId = "HG-Q-A";
        String liveFamilyId = "LIVE_A_ABCDEFAB";
        String otherAuthoredFamilyId = "A_OBSERVE_BIRD";

        when(storyRepository.findById(storyId)).thenReturn(Optional.empty());
        when(storyRepository.save(any(Story.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 이 스토리에는 이미 저작된 anchor 하나가 있고, 그 밑에는 실시간으로 생성된 family가 하나
        // 있다고 가정한다 - 재임포트 payload는 이 anchor를 다시 보내지 않는다(관련 없는 콘텐츠만
        // 재임포트하는 흔한 경우).
        when(anchorRepository.findByStory_Id(storyId))
                .thenReturn(List.of(StoryAnchor.builder().id(authoredAnchorId).build()));
        when(familyRepository.findByAnchor_Story_IdAndOrigin(storyId, FamilyOrigin.LIVE_GENERATED))
                .thenReturn(List.of(StoryActionFamily.builder()
                        .id(liveFamilyId)
                        .origin(FamilyOrigin.LIVE_GENERATED)
                        .build()));

        StoryAsset sceneArt = StoryAsset.builder().slug("home-table").familyId(null).build();
        StoryAsset liveBranchArt = StoryAsset.builder().slug("live-a-abcdefab-01").familyId(liveFamilyId).build();
        StoryAsset otherAuthoredBranchArt =
                StoryAsset.builder().slug("a-observe-bird-01").familyId(otherAuthoredFamilyId).build();
        when(assetRepository.findByStory_IdOrderBySlugAsc(storyId))
                .thenReturn(List.of(sceneArt, liveBranchArt, otherAuthoredBranchArt));

        when(routePromptRepository.findByVersion(anyString())).thenReturn(Optional.empty());
        when(sceneRepository.findByStory_IdOrderBySequenceAsc(storyId)).thenReturn(List.of());
        when(castRepository.findByStory_Id(storyId)).thenReturn(List.of());

        JsonNode body = objectMapper.readTree("""
                {
                  "generatedContent": {"story": {"id": "HG"}, "scenes": [], "fallbacks": []},
                  "packageData": {
                    "story": {"slug": "hansel-gretel", "title": "Hansel & Gretel", "contentVersion": "v1", "availability": "ACTIVE"},
                    "routeContext": {
                      "routePromptVersion": "v1", "routePolicyVersion": "v1",
                      "responseTextNormalizationVersion": "v1", "anchors": {}
                    },
                    "cast": {"castVersion": "v1", "speakers": {}},
                    "assets": [],
                    "prompt": {"version": "v1", "system": ["hi"], "instruction": ["hi"]}
                  }
                }
                """);

        service.importStory(body);

        // anchor는 더 이상 delete-cascade되지 않는다 - 오직 그 아래 AUTHORED family만 scoped-delete된다.
        verify(anchorRepository, org.mockito.Mockito.never()).deleteAll(any());
        verify(familyRepository).deleteByAnchor_IdInAndOrigin(List.of(authoredAnchorId), FamilyOrigin.AUTHORED);

        // asset 삭제 목록에는 LIVE_GENERATED family 소유 삽화가 절대 포함되지 않는다.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StoryAsset>> deletedAssets = ArgumentCaptor.forClass(List.class);
        verify(assetRepository).deleteAll(deletedAssets.capture());
        assertThat(deletedAssets.getValue()).containsExactlyInAnyOrder(sceneArt, otherAuthoredBranchArt);
        assertThat(deletedAssets.getValue()).doesNotContain(liveBranchArt);
    }
}

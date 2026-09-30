package com.qstory.backend.story.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.story.service.StoryCatalogService;
import com.qstory.backend.story.service.StoryContentAssemblyService;
import org.junit.jupiter.api.Test;

/**
 * GET /v1/stories/{storyId}/report-copy - 여러 이야기를 모아 보는 종합 리포트가 이야기별 전략 표를
 * 읽는 경로. 지난 리포트는 이야기가 RETIRED되거나 이용권이 끝나도 보여야 하므로 카탈로그 게이트
 * (StoryCatalogService.get)를 거치지 않는지까지 확인한다.
 */
class StoryControllerReportCopyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StoryCatalogService catalogService = mock(StoryCatalogService.class);
    private final StoryContentAssemblyService assemblyService = mock(StoryContentAssemblyService.class);
    private final CurrentUserResolver currentUserResolver = mock(CurrentUserResolver.class);
    private final StoryController controller =
            new StoryController(catalogService, assemblyService, currentUserResolver);

    @Test
    void returnsTheImportedReportCopyWithoutTheCatalogGate() throws Exception {
        JsonNode reportCopy = objectMapper.readTree(
                "{\"storyId\":\"HG\",\"strategyByFamily\":{\"A_OBSERVE_BIRD\":\"단서를 관찰하고 확인하기\"}}");
        when(assemblyService.reportCopy("HG")).thenReturn(reportCopy);

        JsonNode response = controller.reportCopy("HG");

        assertThat(response.path("strategyByFamily").path("A_OBSERVE_BIRD").asText())
                .isEqualTo("단서를 관찰하고 확인하기");
        verifyNoInteractions(catalogService);
    }

    @Test
    void notImportedStoryIsNotFound() {
        when(assemblyService.reportCopy("ZZ")).thenReturn(null);

        assertThatThrownBy(() -> controller.reportCopy("ZZ"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}

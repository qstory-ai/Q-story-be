package com.qstory.backend.reportanalysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.provider.openrouter.util.OpenRouterClient;
import com.qstory.backend.storyreport.SessionKind;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 리포트 분석 기록의 model_id - 설정한 모델이 아니라 여러 호출에서 실제로 답한 모델들(부른 순서, 중복 없이). */
class ReportAnalysisModelIdTest {

    private static final String PRIMARY = "google/gemini-3.6-flash";
    private static final String FALLBACK = "anthropic/claude-haiku-4.5";

    private final ObjectMapper json = new ObjectMapper();
    private final OpenRouterClient client = mock(OpenRouterClient.class);

    private ReportAnalysisService service() {
        AppProperties config = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(config.providers().openRouter().llmModel()).thenReturn(PRIMARY);
        return new ReportAnalysisService(client, json, config);
    }

    private void answers(String schemaName, String model, String content) throws Exception {
        when(client.structuredCompletion(
                anyString(), anyString(), any(), anyString(), any(), eq(schemaName), anyInt(), anyDouble(),
                any(), anyString(), any()))
                .thenReturn(new OpenRouterClient.StructuredCompletion(json.readTree(content), model));
    }

    private static ReportAnalysisService.Input input(List<Map<String, Object>> turns) {
        return new ReportAnalysisService.Input(SessionKind.HOME, turns, new ObjectMapper().createObjectNode(), null, null);
    }

    @Test
    void recordsEveryModelThatAnsweredInCallOrder() throws Exception {
        answers("q39_extract", PRIMARY, "{\"notAnalyzed\":[],\"observations\":[{\"key\":\"o1\",\"sceneId\":\"S1\","
                + "\"evidenceTurnIds\":[\"t1\"],\"observation\":\"새가 어디로 가는지 궁금해했어요.\","
                + "\"precedingCharacterTurnId\":null,\"initiative\":\"SELF\",\"expressionTypes\":[],\"signals\":[]}]}");
        answers("q39_verify", FALLBACK, "{\"results\":[{\"key\":\"o1\",\"supported\":true,\"overreach\":false,"
                + "\"revisedObservation\":null,\"reason\":\"\"}]}");
        answers("q39_cards", PRIMARY, "{\"cards\":[]}");

        ReportAnalysisService.Analysis analysis = service().analyzeWithModel(
                input(List.of(Map.of("seq", 1, "role", "CHILD", "speaker", "UNVERIFIED", "text", "새는 어디 가?"))));

        assertThat(analysis.modelId()).isEqualTo(PRIMARY + "," + FALLBACK);
        assertThat((List<?>) analysis.result().get("observations")).hasSize(1);
    }

    @Test
    void withoutAnyCallTheConfiguredModelIsKept() {
        ReportAnalysisService.Analysis analysis = service().analyzeWithModel(input(List.of()));

        assertThat(analysis.modelId()).isEqualTo(PRIMARY);
        verify(client, never()).structuredCompletion(
                any(), any(), any(), any(), any(), any(), anyInt(), anyDouble(), any(), any(), any());
    }

    @Test
    void joinedModelsAreCutToTheColumnLength() {
        LinkedHashSet<String> used = new LinkedHashSet<>(List.of("a".repeat(80), "b".repeat(80)));
        assertThat(ReportAnalysisService.joinedModelId(used, PRIMARY)).hasSize(ReportAnalysisService.MODEL_ID_MAX);
        assertThat(ReportAnalysisService.joinedModelId(new LinkedHashSet<>(), PRIMARY)).isEqualTo(PRIMARY);
    }
}

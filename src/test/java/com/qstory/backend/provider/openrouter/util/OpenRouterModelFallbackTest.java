package com.qstory.backend.provider.openrouter.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.choicecopy.service.ChoiceCopyService;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.story.ChildFacingTerms;
import com.qstory.backend.story.service.RoutePromptService;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 텍스트 호출의 대체 모델 - 기본 모델이 맨 앞인 models 배열, 대체가 없으면 예전 요청 그대로. */
class OpenRouterModelFallbackTest {

    private static final String PRIMARY = "google/gemini-3.6-flash";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private OpenRouterClient client(List<String> fallbacks) {
        AppProperties config = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(config.providers().openRouter().apiKey()).thenReturn("key");
        when(config.providers().openRouter().llmModel()).thenReturn(PRIMARY);
        when(config.providers().openRouter().llmFallbackModels()).thenReturn(fallbacks);
        RoutePromptService prompts = mock(RoutePromptService.class);
        when(prompts.requirePrompt(anyString())).thenReturn(new RoutePromptService.Prompt("system", "instruction", "안전 규칙"));
        return new OpenRouterClient(
                mock(HttpClient.class), objectMapper, new RouteResultValidator(mock(ChoiceCopyService.class)),
                prompts, config);
    }

    private JsonNode structuredBody(OpenRouterClient client) throws Exception {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        return objectMapper.readTree(client.buildStructuredBody(
                PRIMARY, "system", List.of(), "{}", schema, "schema_v1", 300, 0));
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    @Test
    void structuredCallSendsModelsWithPrimaryFirst() throws Exception {
        JsonNode body = structuredBody(client(List.of("anthropic/claude-haiku-4.5", "openai/gpt-oss-120b")));

        assertThat(body.path("model").asText()).isEqualTo(PRIMARY);
        assertThat(texts(body.path("models")))
                .containsExactly(PRIMARY, "anthropic/claude-haiku-4.5", "openai/gpt-oss-120b");
        // 대체 모델도 같은 조건(JSON 스키마 등)을 지원해야만 쓰인다.
        assertThat(body.path("provider").path("require_parameters").asBoolean()).isTrue();
    }

    @Test
    void companionCallSendsModelsWithPrimaryFirst() throws Exception {
        var request = new OpenRouterClient.CompanionRequest(
                "너는 누구야?", "v7", "헨젤과 그레텔", "HG-SPK-GRETEL", List.of("HG-SPK-GRETEL"), List.of(),
                null, null, null, ChildFacingTerms.forScene("HG", "HG-F05", List.of()));

        JsonNode withFallback = objectMapper.readTree(
                client(List.of("anthropic/claude-haiku-4.5")).buildCompanionChatBody(request, true));
        assertThat(withFallback.path("model").asText()).isEqualTo(PRIMARY);
        assertThat(texts(withFallback.path("models"))).containsExactly(PRIMARY, "anthropic/claude-haiku-4.5");

        JsonNode without = objectMapper.readTree(client(List.of()).buildCompanionChatBody(request, false));
        assertThat(without.has("models")).isFalse();
        assertThat(without.path("model").asText()).isEqualTo(PRIMARY);
    }

    @Test
    void noFallbackConfigKeepsTheOldRequest() throws Exception {
        assertThat(structuredBody(client(List.of())).has("models")).isFalse();
        assertThat(structuredBody(client(null)).has("models")).isFalse();
        assertThat(structuredBody(client(List.of())).path("model").asText()).isEqualTo(PRIMARY);
    }

    @Test
    void duplicatesAndBlanksAreDropped() {
        ObjectNode root = objectMapper.createObjectNode();
        OpenRouterClient.putModelRouting(root, PRIMARY, Arrays.asList(PRIMARY, " ", null, " openai/gpt-oss-120b "));
        assertThat(texts(root.path("models"))).containsExactly(PRIMARY, "openai/gpt-oss-120b");

        ObjectNode onlyPrimary = objectMapper.createObjectNode();
        OpenRouterClient.putModelRouting(onlyPrimary, PRIMARY, List.of(PRIMARY));
        assertThat(onlyPrimary.has("models")).isFalse();
    }

    @Test
    void usedModelPrefersTheResponseModel() {
        assertThat(OpenRouterClient.usedModel("t", PRIMARY, "anthropic/claude-haiku-4.5"))
                .isEqualTo("anthropic/claude-haiku-4.5");
        assertThat(OpenRouterClient.usedModel("t", PRIMARY, null)).isEqualTo(PRIMARY);
        assertThat(OpenRouterClient.usedModel("t", PRIMARY, "")).isEqualTo(PRIMARY);
    }

    @Test
    void appPropertiesNormalizesTheFallbackList() {
        var openRouter = new AppProperties.OpenRouter(
                "key", PRIMARY, PRIMARY, "img", Arrays.asList(" anthropic/claude-haiku-4.5 ", "", null,
                        "anthropic/claude-haiku-4.5"));
        assertThat(openRouter.llmFallbackModels()).containsExactly("anthropic/claude-haiku-4.5");
        assertThat(new AppProperties.OpenRouter("key", PRIMARY, PRIMARY, "img", null).llmFallbackModels()).isEmpty();
    }
}

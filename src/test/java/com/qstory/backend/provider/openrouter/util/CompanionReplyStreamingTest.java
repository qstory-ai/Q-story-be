package com.qstory.backend.provider.openrouter.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.ProviderException;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.story.ChildFacingTerms;
import com.qstory.backend.choicecopy.service.ChoiceCopyService;
import com.qstory.backend.story.service.RoutePromptService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 그레텔 답 스트리밍 - responseText가 닫히는 순간 정리된 문장을 먼저 넘기고, 최종 검증은 예전과 같다. */
class CompanionReplyStreamingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void findsTheTopLevelStringOnlyOnceItCloses() {
        String key = "responseText";
        assertThat(OpenRouterClient.completedTopLevelStringToken("{\"interactionMode\":\"ANSWER\",\"responseText\":\"응, 무서", key))
                .isNull();
        assertThat(OpenRouterClient.completedTopLevelStringToken(
                "{\"interactionMode\":\"ANSWER\",\n  \"responseText\": \"그는 \\\"안녕\\\"이라고 했어.\", \"speaker", key))
                .isEqualTo("\"그는 \\\"안녕\\\"이라고 했어.\"");
        // 중첩된 같은 이름이나 값 자리의 같은 글자는 보지 않는다.
        assertThat(OpenRouterClient.completedTopLevelStringToken(
                "{\"meta\":{\"responseText\":\"x\"},\"childMeaning\":\"responseText\",\"responseText\":\"좋아!\"", key))
                .isEqualTo("\"좋아!\"");
    }

    private static String sse(String content) throws Exception {
        // 실제 응답처럼 내용을 몇 글자씩 잘라 delta로 보낸다.
        ObjectMapper mapper = new ObjectMapper();
        StringBuilder out = new StringBuilder(": OPENROUTER PROCESSING\n\n");
        for (int index = 0; index < content.length(); index += 7) {
            String piece = content.substring(index, Math.min(content.length(), index + 7));
            out.append("data: {\"choices\":[{\"delta\":{\"content\":")
                    .append(mapper.writeValueAsString(piece)).append("}}]}\n\n");
        }
        return out.append("data: [DONE]\n\n").toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private OpenRouterClient client(String sseBody, ArgumentCaptor<HttpRequest> captor) throws Exception {
        AppProperties config = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(config.providers().openRouter().apiKey()).thenReturn("key");
        when(config.providers().openRouter().llmModel()).thenReturn("google/gemini-3.6-flash");
        RoutePromptService prompts = mock(RoutePromptService.class);
        when(prompts.requirePrompt(anyString())).thenReturn(new RoutePromptService.Prompt("system", "instruction", "안전 규칙"));
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(sseBody.getBytes(StandardCharsets.UTF_8)));
        when(httpClient.send(captor.capture(), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);
        return new OpenRouterClient(
                httpClient, objectMapper, new RouteResultValidator(mock(ChoiceCopyService.class)), prompts, config);
    }

    private static OpenRouterClient.CompanionRequest request() {
        return new OpenRouterClient.CompanionRequest(
                "저 노파는 누구야?", "v7", "헨젤과 그레텔", "HG-SPK-GRETEL", List.of("HG-SPK-GRETEL"), List.of(),
                null, null, null, ChildFacingTerms.forScene("HG", "HG-F05", List.of()));
    }

    @Test
    void sendsTheCleanedReplySentenceBeforeTheRestOfTheJson() throws Exception {
        String content = "{\n  \"interactionMode\": \"ANSWER\",\n  \"responseText\": \"저 노파는 처음 보는 분이야. 너는 어떤 분 같아?\",\n"
                + "  \"speakerId\": \"HG-SPK-GRETEL\",\n  \"topicTag\": null,\n  \"toneTag\": null,\n  \"valueTag\": null,\n"
                + "  \"replyKind\": \"ANSWER\",\n  \"childWantsToEnd\": false,\n  \"childMeaning\": \"누구인지 궁금함\",\n"
                + "  \"asksForHelp\": false,\n  \"proposedActionFamilyId\": null\n}";
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        List<String> early = new ArrayList<>();

        OpenRouterClient.CompanionReply reply = client(sse(content), captor)
                .streamCompanionReply(request(), RequestDeadline.startingNow(10_000), early::add);

        assertThat(early).hasSize(1);
        assertThat(early.get(0)).doesNotContain("노파");
        // 먼저 보낸 문장과 최종 답 문장은 같은 정리를 거쳐 같다.
        assertThat(reply.responseText()).isEqualTo(early.get(0));
        assertThat(reply.replyKind()).isEqualTo("ANSWER");
        assertThat(reply.childMeaning()).isEqualTo("누구인지 궁금함");
    }

    @Test
    void anInvalidFinalReplyStillFails() throws Exception {
        String content = "{\"interactionMode\": \"ANSWER\", \"responseText\": \"안녕!\", \"speakerId\": \"HG-SPK-WITCH\"}";
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        OpenRouterClient client = client(sse(content), captor);

        assertThatThrownBy(() -> client.streamCompanionReply(request(), RequestDeadline.startingNow(10_000), text -> {}))
                .isInstanceOf(ProviderException.class);
    }
}

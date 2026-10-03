package com.qstory.backend.provider.openrouter.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.choicecopy.service.ChoiceCopyService;
import com.qstory.backend.companionchat.DialogueInput;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.story.ActionFamily;
import com.qstory.backend.story.Anchor;
import com.qstory.backend.story.service.RoutePromptService;
import java.net.http.HttpClient;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Q-31 그레텔 대화: 대화 기록·장면이 AI에 실제로 전달되고, 행동 제안은 준비된 범위만 받는지 고정한다. */
class CompanionDialogueTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenRouterClient client = client();

    private OpenRouterClient client() {
        RoutePromptService prompts = mock(RoutePromptService.class);
        when(prompts.requirePrompt(anyString()))
                .thenReturn(new RoutePromptService.Prompt("system", "instruction", "위험한 행동은 막는다."));
        return new OpenRouterClient(
                HttpClient.newHttpClient(), objectMapper, new RouteResultValidator(mock(ChoiceCopyService.class)),
                prompts, mock(AppProperties.class, RETURNS_DEEP_STUBS));
    }

    private static Anchor anchorC() {
        return new Anchor(
                "C", "HG-F07", "마녀가 열쇠고리를 작업대 끝에 내려놓았다.", "HG-SPK-GRETEL", List.of("HG-SPK-GRETEL"),
                List.of(), null, "HG-F07-KEYS-TAKEN", null, List.of("결말의 선공개"),
                List.of(
                        new ActionFamily("C_WAIT_FOR_WITCH_TURN", "마녀가 등을 돌릴 때까지 기다린다.", "좋아", "요약", "b1", "a1", List.of()),
                        new ActionFamily("C_DISTRACT_AND_TAKE_KEYS", "헨젤이 마녀를 부르는 동안 가져온다.", "좋아", "요약", "b2", "a2", List.of())),
                false);
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private OpenRouterClient.CompanionRequest request(DialogueInput dialogue, Anchor inviteAnchor) {
        return new OpenRouterClient.CompanionRequest(
                "헨젤이 마녀를 부르면 돼", "v6", "헨젤과 그레텔", "HG-SPK-GRETEL", List.of("HG-SPK-GRETEL"),
                List.of("마녀의 계획"), null, dialogue, inviteAnchor);
    }

    @Test
    void dialogueInputKeepsRecentTurnsAndDropsMalformedOnes() {
        StringBuilder history = new StringBuilder("[");
        for (int i = 0; i < 15; i++) {
            history.append("{\"role\":\"CHILD\",\"text\":\"말").append(i).append("\"},");
        }
        history.append("{\"role\":\"ROBOT\",\"text\":\"무시\"},{\"role\":\"CHARACTER\",\"text\":\"\"}]");
        DialogueInput input = DialogueInput.fromBody(json(
                "{\"history\":" + history + ",\"anchorId\":\"  \",\"wrapUp\":\"LOUD\","
                        + "\"scene\":{\"title\":\"잠긴 부엌\",\"storySoFar\":[\"1\"],\"recentLines\":[\"그레텔: 열쇠가 저기 있어.\"],\"visual\":\"작업대 끝 열쇠고리\"}}"));

        assertThat(input.history()).hasSize(DialogueInput.MAX_HISTORY_TURNS);
        assertThat(input.history().get(0).text()).isEqualTo("말3");
        assertThat(input.anchorId()).isNull();
        assertThat(input.wrapUp()).isEqualTo("NONE");
        assertThat(input.scene().visual()).isEqualTo("작업대 끝 열쇠고리");
    }

    @Test
    void historySceneAndPreparedActionsReachThePayload() {
        DialogueInput dialogue = DialogueInput.fromBody(json(
                "{\"history\":[{\"role\":\"CHILD\",\"text\":\"열쇠는 어디 있어?\"},{\"role\":\"CHARACTER\",\"text\":\"작업대 끝에 있어.\"}],"
                        + "\"scene\":{\"title\":\"들키지 않고 열쇠를 가져오려면\",\"storySoFar\":[\"부엌에 갇혔다\"],"
                        + "\"recentLines\":[\"그레텔: 열쇠가 저기 있어.\"],\"visual\":\"요리하는 마녀\"},"
                        + "\"executedActions\":[\"잠깐 새를 지켜봤다\"],\"anchorId\":\"HG-Q-C\",\"wrapUp\":\"SUGGEST_RETURN\"}"));
        JsonNode payload = client.companionUserPayload(request(dialogue, anchorC()));

        assertThat(payload.path("conversationSoFar")).hasSize(2);
        assertThat(payload.path("conversationSoFar").get(1).path("who").asText()).isEqualTo("you");
        assertThat(payload.path("currentScene").path("linesJustHeard").get(0).asText()).contains("열쇠가 저기 있어");
        assertThat(payload.path("currentScene").path("visibleInPicture").asText()).isEqualTo("요리하는 마녀");
        assertThat(payload.path("actionsAlreadyTaken").get(0).asText()).isEqualTo("잠깐 새를 지켜봤다");
        assertThat(payload.path("questionInvite").path("preparedActions")).hasSize(2);
        assertThat(payload.path("wrapUp").asText()).isEqualTo("SUGGEST_RETURN");
        // 앵커 자신의 "말하면 안 되는 것"도 합쳐진다.
        assertThat(payload.path("forbiddenKnowledge").toString()).contains("결말의 선공개");
    }

    @Test
    void promptAnswersFirstAndDoesNotEndEveryReplyWithAQuestion() {
        String chat = client.companionSystemPrompt(request(DialogueInput.empty(), null));

        assertThat(chat).contains("먼저 그 질문에 답한다");
        assertThat(chat).contains("모든 답을 질문으로 끝내지 않는다");
        assertThat(chat).contains("childWantsToEnd");
        assertThat(chat).doesNotContain("되질문을 자주");
        assertThat(chat).contains("proposedActionFamilyId는 항상 null");

        String invite = client.companionSystemPrompt(request(DialogueInput.empty(), anchorC()));
        assertThat(invite).contains("preparedActions");
        assertThat(invite).contains("아직 실행했다고 말하지 않는다");
    }

    @Test
    void proposedActionIsKeptOnlyWhenItIsOneOfThisInvitesPreparedActions() {
        String reply = "{\"interactionMode\":\"ANSWER\",\"responseText\":\"헨젤이 부르는 동안 내가 열쇠를 가져오자는 거지?\","
                + "\"speakerId\":\"HG-SPK-GRETEL\",\"topicTag\":null,\"toneTag\":null,\"valueTag\":null,"
                + "\"replyKind\":\"ANSWER\",\"childWantsToEnd\":false,\"childMeaning\":\"헨젤이 마녀를 부르자\","
                + "\"asksForHelp\":false,\"proposedActionFamilyId\":\"%s\"}";

        var invite = client.validateCompanionReply(
                json(reply.formatted("C_DISTRACT_AND_TAKE_KEYS")), request(DialogueInput.empty(), anchorC()));
        assertThat(invite).isNotNull();
        assertThat(invite.proposedActionFamilyId()).isEqualTo("C_DISTRACT_AND_TAKE_KEYS");

        var otherAnchor = client.validateCompanionReply(
                json(reply.formatted("A_OBSERVE_BIRD")), request(DialogueInput.empty(), anchorC()));
        assertThat(otherAnchor.proposedActionFamilyId()).isNull();

        var freeChat = client.validateCompanionReply(
                json(reply.formatted("C_DISTRACT_AND_TAKE_KEYS")), request(DialogueInput.empty(), null));
        assertThat(freeChat.proposedActionFamilyId()).isNull();
    }

    @Test
    void unknownReplyKindIsRejected() {
        String reply = "{\"interactionMode\":\"ANSWER\",\"responseText\":\"그렇구나.\",\"speakerId\":\"HG-SPK-GRETEL\","
                + "\"topicTag\":null,\"toneTag\":null,\"valueTag\":null,\"replyKind\":\"LECTURE\",\"childWantsToEnd\":false,"
                + "\"childMeaning\":\"\",\"asksForHelp\":false,\"proposedActionFamilyId\":null}";
        assertThat(client.validateCompanionReply(json(reply), request(DialogueInput.empty(), null))).isNull();
    }
}

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
import com.qstory.backend.story.ChildFacingTerms;
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
    void promptAnswersBrieflyThenReturnsTheThinkingToTheChild() {
        String chat = client.companionSystemPrompt(request(DialogueInput.empty(), null));

        assertThat(chat).contains("먼저 그 질문에 답한다");
        assertThat(chat).contains("사실은 1~2문장으로 짧게");
        assertThat(chat).contains("생각을 아이에게 돌려준다");
        assertThat(chat).contains("막다른 답으로 끝내지 않는다");
        assertThat(chat).contains("앞에서 한 답을 되풀이하지 않는다");
        // 매번 묻지는 않는다 - 감정·작별·이야기 계속에는 질문하지 않는다.
        assertThat(chat).contains("매번 질문하지는 않는다");
        assertThat(chat).contains("이야기를 계속 듣고 싶다고 하면 질문하지 않고");
        assertThat(chat).contains("proposedActionFamilyId는 항상 null");
        assertThat(chat).contains("할 일을 '~해 보자'고 제안하거나 약속하지 않는다");

        String invite = client.companionSystemPrompt(request(DialogueInput.empty(), anchorC()));
        assertThat(invite).contains("preparedActions");
        assertThat(invite).contains("아직 실행했다고 말하지 않는다");
        assertThat(invite).contains("아이가 그것에 동의하면");
        assertThat(invite).contains("preparedActions에 있는 행동만 제안한다");
        assertThat(invite).doesNotContain("대화만 하는 곳이다");
    }

    @Test
    void inviteWithoutPreparedActionsForbidsProposingThingsToDo() {
        Anchor talkOnly = new Anchor(
                "B", "HG-F05", "과자집 문 앞", "HG-SPK-GRETEL", List.of("HG-SPK-GRETEL"),
                List.of(), null, "HG-F05-ENTER-HOUSE", null, List.of(), List.of(), false);
        String prompt = client.companionSystemPrompt(request(DialogueInput.empty(), talkOnly));

        assertThat(prompt).contains("대화만 하는 곳이다");
        assertThat(prompt).contains("이야기에서 같이 보자");
    }

    private static DialogueInput withHelp(String anchorId) {
        return new DialogueInput(
                List.of(new DialogueInput.Turn("CHILD", "어떤 방법이 있을까?")), null, List.of(), anchorId, "NONE",
                new DialogueInput.Help(1, 4, "마녀가 두 열쇠를 작업대 끝에 내려놨어."));
    }

    @Test
    void helpRequestCarriesTheStepHintOnlyInsideAnInvite() {
        JsonNode payload = client.companionUserPayload(request(withHelp("HG-Q-C"), anchorC()));
        assertThat(payload.path("helpRequest").path("step").asInt()).isEqualTo(1);
        assertThat(payload.path("helpRequest").path("totalSteps").asInt()).isEqualTo(4);
        assertThat(payload.path("helpRequest").path("hint").asText()).contains("작업대 끝");
        assertThat(payload.path("conversationSoFar")).hasSize(1);
        assertThat(client.companionSystemPrompt(request(withHelp("HG-Q-C"), anchorC())))
                .contains("helpRequest.hint는 이번 도움 단계에서 줄 도움의 방향")
                .contains("이미 나온 내용은 되풀이하지 않고");

        // 상시 대화에는 도움 단계가 없다.
        assertThat(client.companionUserPayload(request(withHelp(null), null)).has("helpRequest")).isFalse();
    }

    @Test
    void helpFieldIsReadOnlyWhenWellFormed() {
        DialogueInput ok = DialogueInput.fromBody(json(
                "{\"anchorId\":\"HG-Q-A\",\"help\":{\"step\":2,\"total\":3,\"hint\":\"너는 어떤 게 더 궁금해?\"}}"));
        assertThat(ok.help()).isEqualTo(new DialogueInput.Help(2, 3, "너는 어떤 게 더 궁금해?"));
        assertThat(DialogueInput.fromBody(json("{\"help\":{\"step\":4,\"total\":3,\"hint\":\"x\"}}")).help()).isNull();
        assertThat(DialogueInput.fromBody(json("{\"help\":{\"step\":1,\"total\":3,\"hint\":\" \"}}")).help()).isNull();
        assertThat(DialogueInput.fromBody(json("{}")).help()).isNull();
    }

    @Test
    void helpReplyNeverAsksToOfferHelpAgain() {
        String reply = "{\"interactionMode\":\"ANSWER\",\"responseText\":\"열쇠가 작업대 끝에 있어. 너라면 언제 가져올 것 같아?\","
                + "\"speakerId\":\"HG-SPK-GRETEL\",\"topicTag\":null,\"toneTag\":null,\"valueTag\":null,"
                + "\"replyKind\":\"ANSWER\",\"childWantsToEnd\":false,\"childMeaning\":\"도움을 청함\","
                + "\"asksForHelp\":true,\"proposedActionFamilyId\":null}";
        assertThat(client.validateCompanionReply(json(reply), request(withHelp("HG-Q-C"), anchorC())).asksForHelp()).isFalse();
        assertThat(client.validateCompanionReply(json(reply), request(DialogueInput.empty(), anchorC())).asksForHelp()).isTrue();
    }

    @Test
    void oldWomanIsNeverSentToOrReturnedFromTheModel() {
        DialogueInput dialogue = DialogueInput.fromBody(json(
                "{\"scene\":{\"title\":\"과자집 문 앞의 초대\",\"storySoFar\":[],"
                        + "\"recentLines\":[\"노파: 안에서 따뜻한 빵을 먹고 좀 쉬었다 가렴.\"],\"visual\":\"문을 연 노파\"}}"));
        var terms = ChildFacingTerms.forScene("HG", "HG-F05", dialogue.scene().recentLines());
        var request = new OpenRouterClient.CompanionRequest(
                "저 노파는 누구야?", "v6", "헨젤과 그레텔", "HG-SPK-GRETEL", List.of("HG-SPK-GRETEL"),
                List.of("과자집 노파의 정체"), null, dialogue, null, terms);

        String sentPayload = terms.apply(client.companionUserPayload(request).toString());
        assertThat(sentPayload).doesNotContain("노파").contains("할머니: 안에서");
        String prompt = client.companionSystemPrompt(request);
        assertThat(prompt).contains("'할머니'라고 부른다").doesNotContain("'마녀'라고 부른다");
        // 호칭 규칙 문장 안의 금지어 언급 말고는 "노파"가 없다.
        assertThat(prompt.replace("'노파'라는 말은 쓰지 않는다", "")).doesNotContain("노파");

        String reply = "{\"interactionMode\":\"ANSWER\",\"responseText\":\"처음 보는 노파야. 너는 어떤 분 같아?\","
                + "\"speakerId\":\"HG-SPK-GRETEL\",\"topicTag\":null,\"toneTag\":null,\"valueTag\":null,"
                + "\"replyKind\":\"ANSWER\",\"childWantsToEnd\":false,\"childMeaning\":\"노파가 누구인지 궁금함\","
                + "\"asksForHelp\":false,\"proposedActionFamilyId\":null}";
        var validated = client.validateCompanionReply(json(reply), request);
        assertThat(validated.responseText()).isEqualTo("처음 보는 할머니야. 너는 어떤 분 같아?");
        assertThat(validated.childMeaning()).isEqualTo("할머니가 누구인지 궁금함");
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

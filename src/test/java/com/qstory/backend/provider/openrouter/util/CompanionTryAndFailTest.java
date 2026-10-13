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
import com.qstory.backend.companionchat.DialogueInput;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.story.ActionFamily;
import com.qstory.backend.story.Anchor;
import com.qstory.backend.story.ChildFacingTerms;
import com.qstory.backend.story.service.RoutePromptService;
import java.net.http.HttpClient;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 그레텔 대화 "실패해 보기"(TRY_FAIL): 질문 초대에서 몰래 가져가기·거짓말·혼자 도망치기는 거절 대신 짧은 실패 장면으로,
 * 다치거나 남을 해치는 행동은 지금처럼 안전 리다이렉트로, 그 밖의 말은 예전 그대로 받는지 고정한다(실제 모델 호출 없음).
 */
class CompanionTryAndFailTest {

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

    private OpenRouterClient.CompanionRequest request(String child, Anchor invite, boolean tryAndFail) {
        return request(child, invite, tryAndFail, DialogueInput.empty());
    }

    private OpenRouterClient.CompanionRequest request(
            String child, Anchor invite, boolean tryAndFail, DialogueInput dialogue) {
        return new OpenRouterClient.CompanionRequest(
                child, "v7", "헨젤과 그레텔", "HG-SPK-GRETEL", List.of("HG-SPK-GRETEL"), List.of("마녀의 계획"), null,
                dialogue, invite, ChildFacingTerms.none(), tryAndFail);
    }

    private JsonNode reply(String mode, String text, String kind, String proposed, boolean ends, boolean help) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("interactionMode", mode);
        node.put("responseText", text);
        node.put("speakerId", "HG-SPK-GRETEL");
        node.putNull("topicTag");
        node.putNull("toneTag");
        node.putNull("valueTag");
        node.put("replyKind", kind);
        node.put("childWantsToEnd", ends);
        node.put("childMeaning", "아이 말의 뜻");
        node.put("asksForHelp", help);
        if (proposed == null) {
            node.putNull("proposedActionFamilyId");
        } else {
            node.put("proposedActionFamilyId", proposed);
        }
        return node;
    }

    private static final String FAILURE_SCENE =
            "좋아, 살금살금 열쇠 쪽으로 손을 뻗어 봤어. 그런데 마녀가 뒤척여서 깜짝 놀라 얼른 숨었어. 다른 방법을 찾아보자, 너는 어떻게 하면 좋겠어?";

    @Test
    void sneakingInAnInviteBecomesAFailureSceneThatStaysAtTheSameQuestion() {
        var request = request("마녀가 잘 때 열쇠 몰래 가져가자", anchorC(), true);
        // 모델이 실패 장면에 행동 제안·끝내기·도움 요청을 함께 실어 보내도, 실패해 보기는 이야기를 진행하지 않는다.
        var validated = client.validateCompanionReply(
                reply("ANSWER", FAILURE_SCENE, "TRY_FAIL", "C_WAIT_FOR_WITCH_TURN", true, true), request);

        assertThat(validated.replyKind()).isEqualTo(OpenRouterClient.TRY_FAIL);
        assertThat(validated.interactionMode()).isEqualTo("ANSWER");
        assertThat(validated.proposedActionFamilyId()).isNull();
        assertThat(validated.childWantsToEnd()).isFalse();
        assertThat(validated.asksForHelp()).isFalse();
        assertThat(validated.responseText()).contains("다른 방법을 찾아보자");
    }

    @Test
    void dangerousActionStaysAGentleRedirect() {
        var request = request("마녀를 때리자", anchorC(), true);
        var redirect = client.validateCompanionReply(
                reply("GENTLE_REDIRECT", "그건 다칠 수 있어서 안 돼. 다른 방법을 같이 생각해 보자.", "REDIRECT", null, false, false),
                request);
        assertThat(redirect.interactionMode()).isEqualTo("GENTLE_REDIRECT");
        assertThat(redirect.replyKind()).isEqualTo("REDIRECT");

        // 안전 규칙으로 막으면서 TRY_FAIL을 붙였다면 실패 장면이 아니라 안전 쪽(REDIRECT)으로 읽는다.
        var mixed = client.validateCompanionReply(
                reply("GENTLE_REDIRECT", "그건 위험해서 안 돼. 다른 방법을 찾아보자.", "TRY_FAIL", null, false, false), request);
        assertThat(mixed.replyKind()).isEqualTo("REDIRECT");

        String prompt = client.companionSystemPrompt(request);
        assertThat(prompt).contains("때리기·밀기·불이나 칼 쓰기·오븐에 넣기·가두기");
        assertThat(prompt).contains("실패해 보기가 아니다");
        assertThat(prompt).contains("GENTLE_REDIRECT");
    }

    @Test
    void ordinaryRepliesAndPreparedActionsKeepWorkingAsBefore() {
        var request = request("헨젤이 마녀를 부르면 돼", anchorC(), true);
        var proposal = client.validateCompanionReply(
                reply("ANSWER", "헨젤이 부르는 동안 내가 열쇠를 가져오자는 거지?", "ANSWER", "C_DISTRACT_AND_TAKE_KEYS", false, false),
                request);
        assertThat(proposal.replyKind()).isEqualTo("ANSWER");
        assertThat(proposal.proposedActionFamilyId()).isEqualTo("C_DISTRACT_AND_TAKE_KEYS");

        var close = client.validateCompanionReply(reply("ANSWER", "응, 이야기 계속 듣자!", "CLOSE", null, true, false), request);
        assertThat(close.childWantsToEnd()).isTrue();
    }

    @Test
    void promptTeachesTheFailureSceneOnlyInAnInviteWithTheSwitchOn() {
        String on = client.companionSystemPrompt(request("마녀가 잘 때 열쇠 몰래 가져가자", anchorC(), true));
        assertThat(on).contains("실패해 보기");
        assertThat(on).contains("몰래 가져가기·훔치기, 거짓말하기·속이기");
        assertThat(on).contains("혼자 도망치기");
        assertThat(on).contains("다른 방법을 찾아보자");
        // 공포 상한·처벌 금지·이야기 상태 유지·스포일러 금지.
        assertThat(on).contains("깜짝 놀랐지만 괜찮아");
        assertThat(on).contains("벌을 받거나");
        assertThat(on).contains("아무것도 알아채지 못한다");
        assertThat(on).contains("이야기는 그대로 이 질문 지점이다");
        assertThat(on).contains("정답처럼 먼저 알려 주지 않는다");
        assertThat(on).contains("replyKind는 TRY_FAIL");

        String off = client.companionSystemPrompt(request("마녀가 잘 때 열쇠 몰래 가져가자", anchorC(), false));
        assertThat(off).doesNotContain("실패해 보기").doesNotContain("TRY_FAIL");
        assertThat(off).contains("preparedActions에 없는 행동을 제안하면 그 생각을 받아주되");

        String chat = client.companionSystemPrompt(request("마녀가 잘 때 열쇠 몰래 가져가자", null, true));
        assertThat(chat).doesNotContain("실패해 보기");

        // 도움 버튼 답은 도움만 한다.
        DialogueInput help = new DialogueInput(
                List.of(new DialogueInput.Turn("CHILD", "어떤 방법이 있을까?")), null, List.of(), "HG-Q-C", "NONE",
                new DialogueInput.Help(1, 4, "마녀가 두 열쇠를 작업대 끝에 내려놨어."));
        assertThat(client.companionSystemPrompt(request("도와줘", anchorC(), true, help))).doesNotContain("실패해 보기");
    }

    @Test
    void tryFailIsRejectedWhereItCannotHappen() {
        JsonNode failure = reply("ANSWER", FAILURE_SCENE, "TRY_FAIL", null, false, false);
        // 스위치를 끄거나 상시 대화면 TRY_FAIL은 허용되지 않은 답이다(모델 스키마에도 없다).
        assertThat(client.validateCompanionReply(failure, request("몰래 가져가자", anchorC(), false))).isNull();
        assertThat(client.validateCompanionReply(failure, request("몰래 가져가자", null, true))).isNull();

        JsonNode on = client.companionSchema(
                List.of("HG-SPK-GRETEL"), List.of("C_WAIT_FOR_WITCH_TURN"),
                OpenRouterClient.replyKinds(request("x", anchorC(), true)));
        assertThat(on.path("properties").path("replyKind").path("enum").toString()).contains("TRY_FAIL");
        JsonNode off = client.companionSchema(
                List.of("HG-SPK-GRETEL"), List.of("C_WAIT_FOR_WITCH_TURN"),
                OpenRouterClient.replyKinds(request("x", anchorC(), false)));
        assertThat(off.path("properties").path("replyKind").path("enum").toString()).doesNotContain("TRY_FAIL");
    }

    @Test
    void ledgerVersionFollowsTheSwitch() {
        assertThat(OpenRouterClient.companionPromptVersion(true)).isEqualTo("GRETEL_DIALOGUE_V3");
        assertThat(OpenRouterClient.companionPromptVersion(false)).isEqualTo(OpenRouterClient.COMPANION_PROMPT_VERSION);
    }
}

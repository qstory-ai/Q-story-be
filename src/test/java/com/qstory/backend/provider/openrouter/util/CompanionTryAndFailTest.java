package com.qstory.backend.provider.openrouter.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * 그레텔 대화 시도 장면(TRY_FAIL → 장면 안 대화 → ATTEMPT_END): 질문 초대에서 준비되지 않은 행동은 거절 대신 시도 장면으로,
 * 다치거나 남을 해치는 행동은 지금처럼 안전 리다이렉트로, 준비된 행동은 예전처럼 확인으로 가는지, 장면 안 대화가 상한에서
 * 원래 질문 지점으로 돌아오는지 고정한다(실제 모델 호출 없음).
 */
class CompanionTryAndFailTest {

    private static final String NARRATOR = "HG-SPK-NARRATOR";
    private static final String GRETEL = "HG-SPK-GRETEL";
    private static final OpenRouterClient.AttemptPolicy ON = new OpenRouterClient.AttemptPolicy(true, 2, 4, NARRATOR);

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
                "C", "HG-F07", "마녀가 열쇠고리를 작업대 끝에 내려놓았다.", GRETEL, List.of(GRETEL),
                List.of(), null, "HG-F07-KEYS-TAKEN", null, List.of("결말의 선공개"),
                List.of(
                        new ActionFamily("C_WAIT_FOR_WITCH_TURN", "마녀가 등을 돌릴 때까지 기다린다.", "좋아", "요약", "b1", "a1", List.of()),
                        new ActionFamily("C_DISTRACT_AND_TAKE_KEYS", "헨젤이 마녀를 부르는 동안 가져온다.", "좋아", "요약", "b2", "a2", List.of())),
                false);
    }

    private static DialogueInput withAttempt(int tried, DialogueInput.Active active) {
        return new DialogueInput(List.of(), null, List.of(), "HG-Q-C", "NONE", null, new DialogueInput.Attempt(tried, active));
    }

    private static final DialogueInput.Active IN_SCENE =
            new DialogueInput.Active("창문으로 나가기", "그레텔은 창문 아래로 갔지만 창문이 너무 높았어요. / 뭘 밟고 올라갈까?", 1);

    private OpenRouterClient.CompanionRequest request(
            String child, Anchor invite, OpenRouterClient.AttemptPolicy policy, DialogueInput dialogue) {
        return new OpenRouterClient.CompanionRequest(
                child, "v7", "헨젤과 그레텔", GRETEL, List.of(GRETEL), List.of("마녀의 계획"), null,
                dialogue, invite, ChildFacingTerms.none(), policy);
    }

    private OpenRouterClient.CompanionRequest request(String child, OpenRouterClient.AttemptPolicy policy) {
        return request(child, anchorC(), policy, withAttempt(0, null));
    }

    private ObjectNode reply(String mode, String text, String kind, String proposed, boolean ends, boolean help) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("interactionMode", mode);
        node.putArray("attemptScene");
        node.put("responseText", text);
        node.put("speakerId", GRETEL);
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

    private ObjectNode attemptStart() {
        ObjectNode node = reply("ANSWER", "창문이 너무 높아… 뭘 밟고 올라가면 좋을까?", "TRY_FAIL", "C_WAIT_FOR_WITCH_TURN", true, true);
        ArrayNode scene = node.putArray("attemptScene");
        scene.addObject().put("speakerId", NARRATOR).put("text", "그레텔은 살금살금 창문 아래로 가서 발끝을 들어 보았어요.");
        scene.addObject().put("speakerId", "HG-SPK-WITCH").put("text", "마녀 대사는 버린다");
        scene.addObject().put("speakerId", GRETEL).put("text", "으음, 손이 안 닿아.");
        return node;
    }

    @Test
    void unpreparedActionStartsAnAttemptSceneWithNarratorAndGretelLines() {
        var validated = client.validateCompanionReply(attemptStart(), request("창문으로 도망가자", ON));

        assertThat(validated.replyKind()).isEqualTo(OpenRouterClient.TRY_FAIL);
        // 마녀 같은 다른 인물 줄은 버리고, 내레이터·그레텔 줄만 남는다.
        assertThat(validated.attemptScene()).extracting(OpenRouterClient.SceneLine::speakerId).containsExactly(NARRATOR, GRETEL);
        // 시도 장면은 행동 제안·끝내기·도움 요청이 아니다 - 이야기 진행은 그대로다.
        assertThat(validated.proposedActionFamilyId()).isNull();
        assertThat(validated.childWantsToEnd()).isFalse();
        assertThat(validated.asksForHelp()).isFalse();
    }

    @Test
    void dangerousActionStaysAGentleRedirect() {
        var request = request("마녀를 때리자", ON);
        var redirect = client.validateCompanionReply(
                reply("GENTLE_REDIRECT", "그건 다칠 수 있어서 안 돼. 다른 방법을 같이 생각해 보자.", "REDIRECT", null, false, false),
                request);
        assertThat(redirect.replyKind()).isEqualTo("REDIRECT");
        assertThat(redirect.attemptScene()).isEmpty();

        // 안전 규칙으로 막으면서 TRY_FAIL을 붙였다면 시도 장면이 아니라 안전 쪽(REDIRECT)으로 읽는다.
        ObjectNode mixed = attemptStart();
        mixed.put("interactionMode", "GENTLE_REDIRECT");
        var safe = client.validateCompanionReply(mixed, request);
        assertThat(safe.replyKind()).isEqualTo("REDIRECT");
        assertThat(safe.attemptScene()).isEmpty();

        String prompt = client.companionSystemPrompt(request);
        assertThat(prompt).contains("때리기·밀기·불이나 칼 쓰기·오븐에 넣기·가두기");
        assertThat(prompt).contains("시도 장면이 아니다");
    }

    @Test
    void preparedActionsKeepWorkingAsBeforeEvenInsideAnAttempt() {
        var proposal = client.validateCompanionReply(
                reply("ANSWER", "헨젤이 부르는 동안 내가 열쇠를 가져오자는 거지?", "ANSWER", "C_DISTRACT_AND_TAKE_KEYS", false, false),
                request("헨젤이 마녀를 부르면 돼", ON));
        assertThat(proposal.proposedActionFamilyId()).isEqualTo("C_DISTRACT_AND_TAKE_KEYS");

        // 시도 장면 안에서도, 마지막 말이어도 준비된 행동 제안은 그대로 확인으로 간다.
        var last = new DialogueInput.Active(IN_SCENE.tried(), IN_SCENE.scene(), 4);
        var inScene = client.validateCompanionReply(
                reply("ANSWER", "마녀가 등을 돌릴 때 가져오자는 거지?", "ANSWER", "C_WAIT_FOR_WITCH_TURN", false, false),
                request("마녀가 뒤돌면 가져오자", anchorC(), ON, withAttempt(1, last)));
        assertThat(inScene.replyKind()).isEqualTo("ANSWER");
        assertThat(inScene.proposedActionFamilyId()).isEqualTo("C_WAIT_FOR_WITCH_TURN");
    }

    @Test
    void attemptTalkEndsAtTheTurnLimitAndReturnsToTheQuestion() {
        var midway = client.validateCompanionReply(
                reply("ANSWER", "의자를 끌어와 봤는데 그래도 손이 안 닿아. 또 뭐가 있을까?", "ANSWER", null, false, false),
                request("의자 밟고 올라가자", anchorC(), ON, withAttempt(1, IN_SCENE)));
        assertThat(midway.replyKind()).isEqualTo("ANSWER");

        var last = new DialogueInput.Active(IN_SCENE.tried(), IN_SCENE.scene(), 4);
        var forced = client.validateCompanionReply(
                reply("ANSWER", "그래도 안 되네. 다른 방법을 찾아보자, 너는 어떻게 하면 좋겠어?", "ANSWER", null, false, false),
                request("더 높이 쌓자", anchorC(), ON, withAttempt(1, last)));
        assertThat(forced.replyKind()).isEqualTo(OpenRouterClient.ATTEMPT_END);

        var childStops = client.validateCompanionReply(
                reply("ANSWER", "좋아, 다른 방법을 찾아보자. 너는 어떤 생각이 들어?", "ATTEMPT_END", null, true, false),
                request("그만하고 다른 거 하자", anchorC(), ON, withAttempt(1, IN_SCENE)));
        assertThat(childStops.replyKind()).isEqualTo(OpenRouterClient.ATTEMPT_END);
        assertThat(childStops.childWantsToEnd()).isFalse();

        String prompt = client.companionSystemPrompt(request("의자 밟고 올라가자", anchorC(), ON, withAttempt(1, IN_SCENE)));
        assertThat(prompt).contains("attemptInProgress").contains("ATTEMPT_END").contains("다른 방법을 찾아보자");
        JsonNode payload = client.companionUserPayload(request("의자 밟고 올라가자", anchorC(), ON, withAttempt(1, last)));
        assertThat(payload.path("attemptInProgress").path("lastTurn").asBoolean()).isTrue();
        assertThat(payload.path("attemptInProgress").path("whatWasTried").asText()).isEqualTo("창문으로 나가기");
    }

    @Test
    void attemptsStopAtThePerQuestionCap() {
        var capped = request("굴뚝으로 나가자", anchorC(), ON, withAttempt(2, null));
        assertThat(capped.canStartAttempt()).isFalse();
        assertThat(client.validateCompanionReply(attemptStart(), capped)).isNull();
        assertThat(client.companionSystemPrompt(capped)).contains("더 만들 수 없다").doesNotContain("시도 장면 시작:");
        assertThat(OpenRouterClient.replyKinds(capped)).doesNotContain(OpenRouterClient.TRY_FAIL);
        assertThat(OpenRouterClient.replyKinds(request("굴뚝으로 나가자", ON))).contains(OpenRouterClient.TRY_FAIL);
    }

    @Test
    void promptKeepsTheSafetyAndSpoilerRules() {
        String on = client.companionSystemPrompt(request("창문으로 도망가자", ON));
        assertThat(on).contains("시도 장면 시작:");
        assertThat(on).contains("창문으로 나가자, 굴뚝으로 나가자, 마녀에게 부탁하자, 새를 불러 도와달라고 하자");
        assertThat(on).contains("깜짝 놀랐지만 괜찮아");
        assertThat(on).contains("벌을 받거나");
        assertThat(on).contains("알아채지 못한다");
        assertThat(on).contains("이야기 주 흐름(열쇠·탈출·결말)을 바꾸지 않는다");
        // 지난 평가에서 나온 "정답 먼저 나열"을 막는다.
        assertThat(on).contains("preparedActions의 방법을 바로 알려 주거나 둘 이상 나열하지 않는다");

        String off = client.companionSystemPrompt(request("창문으로 도망가자", OpenRouterClient.AttemptPolicy.OFF));
        assertThat(off).doesNotContain("시도 장면").doesNotContain("TRY_FAIL");
        assertThat(off).contains("preparedActions에 없는 행동을 제안하면 그 생각을 받아주되");

        String chat = client.companionSystemPrompt(request("창문으로 도망가자", null, ON, DialogueInput.empty()));
        assertThat(chat).doesNotContain("시도 장면");

        DialogueInput help = new DialogueInput(
                List.of(new DialogueInput.Turn("CHILD", "어떤 방법이 있을까?")), null, List.of(), "HG-Q-C", "NONE",
                new DialogueInput.Help(1, 4, "마녀가 두 열쇠를 작업대 끝에 내려놨어."));
        assertThat(client.companionSystemPrompt(request("도와줘", anchorC(), ON, help))).doesNotContain("시도 장면");
    }

    /**
     * 운영 재현(HG-F04): "새 뒤쪽의 빛나는 길로 가보고 싶어" → "응 빛나는 길로 가보자"에 그레텔이 ANSWER로 말로만 동의했다.
     * 하고 싶다는 말·준비되지 않은 이동, 앞에서 나온 준비되지 않은 행동에 대한 동의도 시도 장면을 시작하는 제안이다.
     */
    @Test
    void wishesMovesAndAgreementToAnUnpreparedActionStartAnAttempt() {
        String prompt = client.companionSystemPrompt(request("응 빛나는 길로 가보자", ON));
        assertThat(prompt).contains("'~하고 싶어'", "'~로 가 보고 싶어'");
        assertThat(prompt).contains("저 길로 가 보자");
        assertThat(prompt).contains("앞 대화에서 아이나 네가 꺼낸 preparedActions에 없는 행동");
        // 가 보는 시도가 다음 장면(과자집)을 먼저 보여 주지 않게 - 첫 평가에서 "커다란 집이 보여!"가 나왔다.
        assertThat(prompt).contains("새 장소에 도착하거나");
        // 예전 규칙("동의만 한 말은 시도 장면이 아니다")이 "응 ~가 보자"를 동의로 읽게 했다.
        assertThat(prompt).doesNotContain("질문·감정·동의만 한 말은 시도 장면이 아니다");
    }

    /** 방어: 질문 초대에서 준비되지 않은 행동에 "같이 걸어가 볼까?"처럼 하겠다고 약속하는 답을 막는다(시도 장면 켜짐·꺼짐·상한 모두). */
    @Test
    void inviteNeverPromisesToCarryOutAnUnpreparedAction() {
        String rule = "하겠다고 동의·약속하거나 하는 중인 것처럼 말하지 않는다";
        assertThat(client.companionSystemPrompt(request("응 빛나는 길로 가보자", ON))).contains(rule);
        assertThat(client.companionSystemPrompt(request("응 빛나는 길로 가보자", OpenRouterClient.AttemptPolicy.OFF))).contains(rule);
        assertThat(client.companionSystemPrompt(request("응 빛나는 길로 가보자", anchorC(), ON, withAttempt(2, null)))).contains(rule);
    }

    @Test
    void attemptSceneComesBeforeTheReplyInTheSchemaOnlyWhenAnAttemptCanStart() {
        JsonNode on = client.companionSchema(
                List.of(GRETEL), List.of("C_WAIT_FOR_WITCH_TURN"), OpenRouterClient.replyKinds(request("x", ON)),
                List.of(NARRATOR, GRETEL));
        List<String> order = new java.util.ArrayList<>();
        on.path("properties").fieldNames().forEachRemaining(order::add);
        assertThat(order.indexOf("attemptScene")).isLessThan(order.indexOf("responseText"));
        assertThat(on.path("properties").path("replyKind").path("enum").toString()).contains("TRY_FAIL");

        JsonNode off = client.companionSchema(
                List.of(GRETEL), List.of("C_WAIT_FOR_WITCH_TURN"),
                OpenRouterClient.replyKinds(request("x", OpenRouterClient.AttemptPolicy.OFF)), List.of());
        assertThat(off.path("properties").has("attemptScene")).isFalse();
        assertThat(off.path("properties").path("replyKind").path("enum").toString())
                .doesNotContain("TRY_FAIL").doesNotContain("ATTEMPT_END");
        // 스위치를 끄면 시도 장면 답은 허용되지 않는다.
        assertThat(client.validateCompanionReply(attemptStart(), request("창문으로 도망가자", OpenRouterClient.AttemptPolicy.OFF)))
                .isNull();
    }

    @Test
    void streamingHoldsTheReplyTextBackOnlyWhenASceneIsComing() {
        assertThat(OpenRouterClient.EMPTY_ATTEMPT_SCENE.matcher("{\"interactionMode\":\"ANSWER\",\"attemptScene\": [ ],\"responseText\":\"x\"").find())
                .isTrue();
        assertThat(OpenRouterClient.EMPTY_ATTEMPT_SCENE.matcher("{\"attemptScene\":[{\"speakerId\":\"N\",\"text\":\"a\"}],\"responseText\":\"x\"").find())
                .isFalse();
    }

    @Test
    void dialogueInputReadsTheAttemptStateAndDropsMalformedOnes() throws Exception {
        DialogueInput ok = DialogueInput.fromBody(objectMapper.readTree(
                "{\"anchorId\":\"HG-Q-C\",\"attempt\":{\"tried\":1,\"active\":{\"tried\":\"창문으로 나가기\",\"scene\":\"창문이 높았다\",\"turn\":2}}}"));
        assertThat(ok.attempt().tried()).isEqualTo(1);
        assertThat(ok.attempt().active().turn()).isEqualTo(2);
        DialogueInput broken = DialogueInput.fromBody(objectMapper.readTree(
                "{\"attempt\":{\"tried\":1,\"active\":{\"tried\":\" \",\"scene\":\"x\",\"turn\":0}}}"));
        assertThat(broken.attempt().active()).isNull();
        assertThat(DialogueInput.fromBody(objectMapper.readTree("{}")).attempt()).isEqualTo(DialogueInput.Attempt.NONE);
    }

    /**
     * 운영 재확인(HG-F04, V4): 시도 장면 내레이터 줄이 "그레텔과 아이는 …"처럼 대화하는 아이를 이야기 속 인물로 넣었다.
     * 장면 줄은 캐스트만 행동한다 - "X와 아이는"은 X만 남기고, 그 밖에 아이·너·친구가 인물로 나오는 줄은 버린다.
     */
    @Test
    void attemptSceneKeepsTheChildOutOfTheStory() {
        ObjectNode node = reply("ANSWER", "풀숲에 막혀서 더 갈 수가 없네… 다른 방법이 있을까?", "TRY_FAIL", null, false, false);
        ArrayNode scene = node.putArray("attemptScene");
        scene.addObject().put("speakerId", NARRATOR).put("text", "그레텔과 아이는 새 뒤쪽의 빛나는 길로 걸어가 보았어요.");
        scene.addObject().put("speakerId", GRETEL).put("text", "너도 같이 가자!");
        scene.addObject().put("speakerId", NARRATOR).put("text", "하지만 풀숲이 빽빽해서 더 들어갈 수 없었어요.");

        var validated = client.validateCompanionReply(node, request("나도 같이 갈래! 빛나는 길로 가자", ON));

        assertThat(validated.replyKind()).isEqualTo(OpenRouterClient.TRY_FAIL);
        assertThat(validated.attemptScene()).extracting(OpenRouterClient.SceneLine::text).containsExactly(
                "그레텔은 새 뒤쪽의 빛나는 길로 걸어가 보았어요.", "하지만 풀숲이 빽빽해서 더 들어갈 수 없었어요.");

        // 고쳐 쓰는 모양들 - 받침에 맞는 조사로 남기고, 아이를 앞에 둔 경우도 뺀다.
        assertThat(OpenRouterClient.sceneLineWithoutChild("헨젤과 그레텔과 우리 친구는 손을 잡았어요."))
                .isEqualTo("헨젤과 그레텔은 손을 잡았어요.");
        assertThat(OpenRouterClient.sceneLineWithoutChild("그레텔과 헨젤, 그리고 친구는 함께 걸어갔어요."))
                .isEqualTo("그레텔과 헨젤은 함께 걸어갔어요.");
        assertThat(OpenRouterClient.sceneLineWithoutChild("헨젤과 아이가 덤불을 헤쳐 보았어요.")).isEqualTo("헨젤이 덤불을 헤쳐 보았어요.");
        assertThat(OpenRouterClient.sceneLineWithoutChild("아이와 그레텔은 살금살금 다가갔어요.")).isEqualTo("그레텔은 살금살금 다가갔어요.");
        assertThat(OpenRouterClient.sceneLineWithoutChild("아이는 그레텔 옆에 섰어요.")).isNull();
        // 이야기 속 남매를 가리키는 말·감탄사·'너무'는 그대로 둔다.
        assertThat(OpenRouterClient.sceneLineWithoutChild("두 아이는 손을 꼭 잡았어요.")).isEqualTo("두 아이는 손을 꼭 잡았어요.");
        assertThat(OpenRouterClient.sceneLineWithoutChild("아이들은 덤불 앞에 멈췄어요.")).isEqualTo("아이들은 덤불 앞에 멈췄어요.");
        assertThat(OpenRouterClient.sceneLineWithoutChild("아이고, 너무 빽빽해.")).isEqualTo("아이고, 너무 빽빽해.");

        String prompt = client.companionSystemPrompt(request("나도 같이 갈래! 빛나는 길로 가자", ON));
        assertThat(prompt).contains("'아이', '너', '친구'를 장면 인물로 쓰지 않는다");
    }

    /**
     * 운영 재확인(HG-F04, V4): 시도 장면 뒤 그레텔이 "저 새를 그냥 가만히 지켜볼까? 아니면 말을 건네볼까?"처럼 준비된 두 행동을
     * 나란히 내놓았다. 시도 장면 시작(TRY_FAIL)·장면 안 대화·ATTEMPT_END 답 모두 열린 질문으로, 준비된 행동은 하나까지만.
     */
    @Test
    void attemptRepliesOfferAtMostOnePreparedAction() {
        String rule = "preparedActions의 행동을 둘 이상 늘어놓거나 '~할까? 아니면 ~할까?'처럼 고르게 하지 않는다";
        assertThat(client.companionSystemPrompt(request("새 뒤쪽의 빛나는 길로 가보고 싶어", ON))).contains(rule);
        assertThat(client.companionSystemPrompt(request("그럼 어떻게 해?", anchorC(), ON, withAttempt(1, IN_SCENE)))).contains(rule);
        var last = new DialogueInput.Active(IN_SCENE.tried(), IN_SCENE.scene(), 4);
        assertThat(client.companionSystemPrompt(request("그만하자", anchorC(), ON, withAttempt(1, last)))).contains(rule);
        // 스위치를 끈 쪽(V2_1)은 그대로다.
        assertThat(client.companionSystemPrompt(request("그럼 어떻게 해?", OpenRouterClient.AttemptPolicy.OFF))).doesNotContain(rule);
    }

    /** 운영 확인: 장면이 "걸어갔어요" → "길이 막혀 있네"로 해 보는 모습 없이 결과만 나왔다 - 가 보기·애써 해 보기·막힘 순서로 그린다. */
    @Test
    void attemptSceneShowsTheTryBeforeTheFailure() {
        String prompt = client.companionSystemPrompt(request("새 뒤쪽의 빛나는 길로 가보고 싶어", ON));
        assertThat(prompt).contains("TRY_FAIL의 attemptScene은 3줄이고, 아이 생각을 정말로 해 보는 장면이다");
        assertThat(prompt).contains("~해 보았지만, (막힌 까닭)", "해 보기도 전에 '막혀 있네', '안 되겠다'처럼 결과부터 말하지 않는다");
        assertThat(prompt).doesNotContain("attemptScene은 2~3줄");
    }

    /** 운영 평가: 그레텔 장면 줄이 "조금만 더 가 볼까?"처럼 물었다 - 장면 줄의 물음표는 혼잣말 말줄임표로 바꾼다. */
    @Test
    void sceneLinesNeverAsk() {
        assertThat(OpenRouterClient.withoutQuestion("영차, 영차… 조금만 더 가 볼까?")).isEqualTo("영차, 영차… 조금만 더 가 볼까…");
        assertThat(OpenRouterClient.withoutQuestion("저쪽에 뭐가 있을까? 조금만 더 가서 봐야지."))
                .isEqualTo("저쪽에 뭐가 있을까… 조금만 더 가서 봐야지.");
        assertThat(OpenRouterClient.withoutQuestion("으차, 조금만 더 가면 나오겠지…?")).isEqualTo("으차, 조금만 더 가면 나오겠지…");
        assertThat(OpenRouterClient.withoutQuestion("언제 잠들지?!")).isEqualTo("언제 잠들지…");
        assertThat(OpenRouterClient.withoutQuestion("영차, 영차… 풀을 헤치고 조금만 더!")).isEqualTo("영차, 영차… 풀을 헤치고 조금만 더!");
    }

    @Test
    void ledgerVersionFollowsTheSwitch() {
        assertThat(OpenRouterClient.companionPromptVersion(true)).isEqualTo("GRETEL_DIALOGUE_V6");
        assertThat(OpenRouterClient.COMPANION_PROMPT_VERSION).isEqualTo("GRETEL_DIALOGUE_V2_1");
        assertThat(OpenRouterClient.companionPromptVersion(false)).isEqualTo(OpenRouterClient.COMPANION_PROMPT_VERSION);
    }
}

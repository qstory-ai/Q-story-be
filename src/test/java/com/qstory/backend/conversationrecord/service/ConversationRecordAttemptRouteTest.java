package com.qstory.backend.conversationrecord.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.qstory.backend.provider.openrouter.util.OpenRouterClient;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 대화 원장에 시도 장면의 시작·장면 안 대화·마침과 장면 줄이 구분돼 남는지(무엇을 해 봤고 몇 번 이어졌는지). */
class ConversationRecordAttemptRouteTest {

    private static OpenRouterClient.CompanionReply reply(String kind, List<OpenRouterClient.SceneLine> scene) {
        return new OpenRouterClient.CompanionReply(
                "ANSWER", "뭘 밟고 올라갈까?", "HG-SPK-GRETEL", null, null, null, kind, false, "뜻", false, null, scene);
    }

    @Test
    void attemptTurnsGetTheirOwnRoutes() {
        var start = reply(OpenRouterClient.TRY_FAIL,
                List.of(new OpenRouterClient.SceneLine("HG-SPK-NARRATOR", "창문이 너무 높았어요.")));
        assertThat(ConversationRecordService.companionRoute(start, null)).isEqualTo("TRY_AND_FAIL");
        assertThat(ConversationRecordService.responseWithScene(start))
                .isEqualTo("[HG-SPK-NARRATOR] 창문이 너무 높았어요. / 뭘 밟고 올라갈까?");
        assertThat(ConversationRecordService.companionRoute(reply("ANSWER", List.of()), 2)).isEqualTo("TRY_AND_FAIL_TALK_2");
        assertThat(ConversationRecordService.companionRoute(reply(OpenRouterClient.ATTEMPT_END, List.of()), 4))
                .isEqualTo("TRY_AND_FAIL_END_4");
        assertThat(ConversationRecordService.companionRoute(reply("ANSWER", List.of()), null)).isEqualTo("ANSWER");
        assertThat(ConversationRecordService.responseWithScene(reply("ANSWER", List.of()))).isEqualTo("뭘 밟고 올라갈까?");
    }
}

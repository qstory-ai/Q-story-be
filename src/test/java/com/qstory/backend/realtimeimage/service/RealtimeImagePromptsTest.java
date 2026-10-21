package com.qstory.backend.realtimeimage.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.companionchat.service.CompanionChatPipelineService;
import com.qstory.backend.provider.higgsfield.util.HiggsfieldImageClient;
import com.qstory.backend.provider.openrouter.util.OpenRouterClient;
import com.qstory.backend.story.Anchor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 실시간 삽화 프롬프트가 정적 삽화와 같은 공통 글을 쓰고, 상한 5,000자를 지키며, 인물 시트 라벨을 바르게 고르는지 고정한다. */
class RealtimeImagePromptsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void branchPromptStartsWithTheSharedStyleAndStaysUnderTheFlareLimit() throws Exception {
        var brief = objectMapper.readTree("{\"characters\":[\"HANSEL\",\"GRETEL\"],\"action\":\"Gretel calls the white bird softly\","
                + "\"composition\":\"medium two-shot\",\"continuityFacts\":[\"forest path\",\"bird on a branch\"],\"negativePrompt\":\"witch\"}");
        String prompt = RealtimeImagePrompts.branchPrompt(
                "하얀 새가 자꾸 돌아본다.", brief, List.of("HANSEL", "GRETEL"),
                Map.of("GRETEL", List.of("금발 땋은 머리", "빨간 치마")), null);

        assertThat(prompt).startsWith("Create one final illustration for a cohesive Hansel and Gretel storybook");
        assertThat(prompt).contains("CHARACTER CONSISTENCY").contains("Gretel (금발 땋은 머리; 빨간 치마)")
                .contains("Required action: Gretel calls the white bird softly").contains("Do not include: witch")
                .contains("bottom 40%");
        assertThat(prompt.length()).isLessThanOrEqualTo(5_000);
    }

    @Test
    void attemptPromptCarriesTheSceneLinesAndTheBlockedDescription() {
        String prompt = RealtimeImagePrompts.attemptPrompt(
                "마녀가 열쇠고리를 작업대 끝에 내려놓았다.",
                List.of("그레텔은 창문 아래로 가 보았어요.", "까치발을 들고 손을 뻗었어요.", "하지만 창문은 너무 높았어요."),
                "까치발로 팔을 뻗어도 높은 창문에 손이 닿지 않음", List.of("HANSEL", "GRETEL", "WITCH"), Map.of(), null);

        assertThat(prompt).contains("oh no, this way does not work").contains("창문은 너무 높았어요")
                .contains("WHAT THE PICTURE SHOWS (Korean): 까치발로").contains("the witch (the same old woman, cold expression)")
                .contains("Nobody is hurt");
        assertThat(prompt.length()).isLessThanOrEqualTo(5_000);
    }

    @Test
    void veryLongSceneTextIsClippedSoThePromptNeverExceedsTheLimit() {
        String longLine = "아주 긴 장면 줄입니다. ".repeat(400);
        String prompt = RealtimeImagePrompts.attemptPrompt(
                "상황", List.of(longLine, longLine), longLine, List.of("HANSEL", "GRETEL"), Map.of(), null);
        assertThat(prompt.length()).isLessThanOrEqualTo(5_000);
    }

    @Test
    void attemptCharactersAddTheWitchOrBirdOnlyWhenTheSceneMentionsThem() {
        Anchor anchor = new Anchor("C", "HG-F07", "마녀가 열쇠고리를 내려놓았다.", "HG-SPK-GRETEL",
                List.of("HG-SPK-GRETEL"), List.of(), null, "HG-F07-KEYS-TAKEN", null, List.of(), List.of(), false);
        OpenRouterClient.CompanionReply reply = new OpenRouterClient.CompanionReply(
                "ANSWER", "창문이 너무 높아…", "HG-SPK-GRETEL", null, null, null, OpenRouterClient.TRY_FAIL, false, "", false, null,
                List.of(new OpenRouterClient.SceneLine("HG-SPK-NARRATOR", "그레텔은 창문 아래로 가 보았어요.")), "c-try-high-window");
        assertThat(CompanionChatPipelineService.attemptCharacters(anchor, reply))
                .containsExactly("HANSEL", "GRETEL", "WITCH");

        Anchor forest = new Anchor("A", "HG-F04", "하얀 새가 자꾸 돌아본다.", "HG-SPK-GRETEL",
                List.of("HG-SPK-GRETEL"), List.of(), null, "HG-F04-END", null, List.of(), List.of(), false);
        OpenRouterClient.CompanionReply bushes = new OpenRouterClient.CompanionReply(
                "ANSWER", "덤불이 너무 빽빽해…", "HG-SPK-GRETEL", null, null, null, OpenRouterClient.TRY_FAIL, false, "", false, null,
                List.of(new OpenRouterClient.SceneLine("HG-SPK-NARRATOR", "덤불을 헤쳐 보았지만 더 갈 수 없었어요.")), null);
        assertThat(CompanionChatPipelineService.attemptCharacters(forest, bushes))
                .containsExactly("HANSEL", "GRETEL", "WHITE_BIRD");
    }

    @Test
    void higgsfieldStatusParsingHandlesTerminalStatesAndErrors() throws Exception {
        var done = HiggsfieldImageClient.parseStatus(objectMapper.readTree(
                "{\"status\":\"completed\",\"images\":[{\"url\":\"https://x/y.png\"}]}"));
        assertThat(done.terminal()).isTrue();
        assertThat(done.completed()).isTrue();
        assertThat(done.imageUrl()).isEqualTo("https://x/y.png");

        var nsfw = HiggsfieldImageClient.parseStatus(objectMapper.readTree(
                "{\"status\":\"nsfw\",\"error\":\"content safety\"}"));
        assertThat(nsfw.terminal()).isTrue();
        assertThat(nsfw.completed()).isFalse();
        assertThat(nsfw.error()).isEqualTo("content safety");

        var running = HiggsfieldImageClient.parseStatus(objectMapper.readTree("{\"status\":\"in_progress\"}"));
        assertThat(running.terminal()).isFalse();
    }

    @Test
    void labelsAreNormalizedFromSpeakerIds() {
        assertThat(RealtimeImageService.normalizeLabels(List.of("HG-SPK-GRETEL", "HANSEL", "HG-SPK-GRETEL", "witch")))
                .containsExactly("GRETEL", "HANSEL", "WITCH");
    }
}

package com.qstory.backend.realtimeimage.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;

/**
 * 실시간 삽화 프롬프트 - 미리 만드는 정적 삽화(fe/scripts/illustrations/style-01.mjs)와 같은 공통 글(스타일 01, 디자이너 문서의
 * 고정 프롬프트를 줄인 판)·같은 구도 규칙 위에 장면 칸만 바꿔 끼운다. 그래야 실시간 그림이 정적 그림 사이에 섞여도 티가 나지 않는다.
 * Flare 상한 5,000자를 넘지 않게 장면 글은 자른다.
 */
public final class RealtimeImagePrompts {

    private static final int MAX_PROMPT_CHARS = 5_000;
    private static final int MAX_SCENE_CHARS = 700;

    /** 플레이어 화면에 맞춘 구도 - 아래 약 40%는 자막·대화 패널이 덮는다. */
    static final String COMPOSITION_RULE =
            "Keep characters and the key action in the UPPER and MIDDLE of the frame (faces at 20-55% of the height); "
                    + "the app covers the bottom 40% with subtitles, so leave it calm (ground, floor, foliage) with no faces, "
                    + "hands or key props. Characters visible from head to at least the knees.";

    static final String NEGATIVE =
            "Gentle for children ages 5-9: tense moments are never gory or horrifying. No UI, lettering or watermark.";

    /** 인물 라벨 → 장면 설명에 쓸 영어 이름. */
    private static final Map<String, String> CHARACTER_NAMES = Map.of(
            "HANSEL", "Hansel", "GRETEL", "Gretel", "FATHER", "the father", "STEPMOTHER", "the stepmother",
            "OLD_WOMAN", "the old woman", "WITCH", "the witch (the same old woman, cold expression)",
            "WHITE_BIRD", "a small snow-white bird", "WHITE_DUCK", "a large white duck");

    private static final String COMMON = loadCommon();

    private RealtimeImagePrompts() {}

    /**
     * 새 선택지(행동 전개) 그림 - LiveBranchExecutionWorker의 초안 imageBrief(characters/action/composition/continuityFacts/
     * negativePrompt)와 질문 지점 상황으로 장면 칸을 채운다.
     */
    public static String branchPrompt(
            String anchorSummary, JsonNode imageBrief, List<String> characterLabels,
            Map<String, List<String>> factsByLabel, String background) {
        List<String> scene = new ArrayList<>();
        scene.add("STORY: In the question moment \"" + clip(anchorSummary, 200) + "\", the child chose a new action. "
                + "Required action: " + clip(imageBrief.path("action").asText(""), 240) + ".");
        String continuity = String.join("; ", toStrings(imageBrief.path("continuityFacts")));
        if (!continuity.isBlank()) {
            scene.add("CONTINUITY: " + clip(continuity, 300) + ".");
        }
        String composition = imageBrief.path("composition").asText("");
        scene.add("COMPOSITION: " + (composition.isBlank() ? "Medium shot focused on the chosen action, faces and hands clearly readable." : clip(composition, 200))
                + " " + COMPOSITION_RULE);
        String negative = imageBrief.path("negativePrompt").asText("");
        if (!negative.isBlank()) {
            scene.add("Do not include: " + clip(negative, 160) + ".");
        }
        return assemble(characterLabels, factsByLabel, background, scene);
    }

    /**
     * 시도 장면 막힘 그림 - 그레텔이 들려준 장면 3줄(가서 → 해 보고 → 막힘)과 막힌 모습 설명으로 장면 칸을 채운다. 마지막 줄의
     * "안 됐어요"가 그림의 주제다. 아무도 다치지 않는다.
     */
    public static String attemptPrompt(
            String anchorSummary, List<String> sceneLines, String blockedDescription, List<String> characterLabels,
            Map<String, List<String>> factsByLabel, String background) {
        List<String> scene = new ArrayList<>();
        String lines = String.join(" / ", sceneLines);
        scene.add("STORY: A gentle \"oh no, this way does not work\" moment in the question situation \""
                + clip(anchorSummary, 160) + "\". The attempt, read in Korean: " + clip(lines, MAX_SCENE_CHARS) + ".");
        if (blockedDescription != null && !blockedDescription.isBlank()) {
            scene.add("WHAT THE PICTURE SHOWS (Korean): " + clip(blockedDescription, 200) + ".");
        }
        scene.add("Show the attempt and clearly why it is blocked. Nobody is hurt; the mood stays gentle and a little funny, never scary.");
        scene.add("COMPOSITION: Medium shot; the blocked attempt is the main subject, large in the frame. " + COMPOSITION_RULE);
        return assemble(characterLabels, factsByLabel, background, scene);
    }

    private static String assemble(
            List<String> characterLabels, Map<String, List<String>> factsByLabel, String background, List<String> scene) {
        List<String> lines = new ArrayList<>();
        lines.add(COMMON);
        lines.add("");
        if (characterLabels.isEmpty()) {
            lines.add("No people in this scene.");
        } else {
            List<String> cast = new ArrayList<>();
            for (String label : characterLabels) {
                String name = CHARACTER_NAMES.getOrDefault(label, label.toLowerCase().replace('_', ' '));
                List<String> facts = factsByLabel.getOrDefault(label, List.of());
                cast.add(facts.isEmpty() ? name : name + " (" + clip(String.join("; ", facts), 220) + ")");
            }
            lines.add("CHARACTERS IN THIS SCENE (match the attached sheets; no one else): " + String.join(". ", cast) + ".");
        }
        lines.addAll(scene);
        if (background != null && !background.isBlank()) {
            lines.add("BACKGROUND: " + background);
        }
        lines.add(NEGATIVE);
        String prompt = String.join("\n", lines);
        return prompt.length() <= MAX_PROMPT_CHARS ? prompt : prompt.substring(0, MAX_PROMPT_CHARS);
    }

    static String clip(String value, int max) {
        if (value == null) return "";
        String trimmed = value.replace('\n', ' ').trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    private static List<String> toStrings(JsonNode array) {
        List<String> list = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(node -> {
                if (node.isTextual() && !node.asText().isBlank()) list.add(node.asText());
            });
        }
        return list;
    }

    private static String loadCommon() {
        try (InputStream stream = new ClassPathResource("realtime-image/style-01-common.txt").getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            throw new IllegalStateException("realtime-image/style-01-common.txt missing", error);
        }
    }
}

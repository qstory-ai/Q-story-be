package com.qstory.backend.companionchat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 그레텔 대화(상시 대화·질문 초대)가 한 턴마다 함께 보내는 맥락. 모두 선택 항목이라 예전 클라이언트가
 * 보내는 {transcript}만 있는 요청도 그대로 받는다.
 *
 * <p>대화 기록과 장면 정보는 아이가 이미 들은 공개 대본·자기 대화라 클라이언트가 들고 있다가 보낸다 -
 * 서버는 길이만 자르고, 이 값으로 권한이나 분기를 정하지 않는다(질문 초대의 실행 가능한 행동은 서버가
 * 앵커에서 직접 읽는다).
 *
 * @param history       최근 대화, 오래된 것부터. role은 CHILD 또는 CHARACTER.
 * @param scene         지금 장면 - null이면 장면 정보 없이 답한다.
 * @param executedActions 이번 이야기에서 이미 실행한 행동의 뜻(예: "잠깐 멈춰서 하얀 새를 지켜봤다").
 * @param anchorId      질문 초대 중이면 그 질문 지점 id, 상시 대화면 null.
 * @param wrapUp        긴 대화 정리 신호 - NONE / SUGGEST_RETURN(이야기로 돌아가자고 제안) / CLOSE(마무리 인사).
 */
public record DialogueInput(
        List<Turn> history,
        Scene scene,
        List<String> executedActions,
        String anchorId,
        String wrapUp) {

    public static final int MAX_HISTORY_TURNS = 12;
    public static final int MAX_TEXT = 240;
    public static final Set<String> WRAP_UPS = Set.of("NONE", "SUGGEST_RETURN", "CLOSE");

    public record Turn(String role, String text) {}

    /**
     * @param title       장면 제목
     * @param storySoFar  지금 장면까지의 줄거리(장면마다 한 줄) - 아직 나오지 않은 장면은 들어오지 않는다.
     * @param recentLines 지금 장면에서 방금 들은 대사(화자: 대사)
     * @param visual      지금 그림에 보이는 것
     */
    public record Scene(String title, List<String> storySoFar, List<String> recentLines, String visual) {}

    public static DialogueInput empty() {
        return new DialogueInput(List.of(), null, List.of(), null, "NONE");
    }

    public boolean isInvite() {
        return anchorId != null;
    }

    /** 요청 본문에서 읽는다. 형식이 어긋난 항목은 버리고, 길이는 자른다 - 이 맥락 때문에 대화가 실패하면 안 된다. */
    public static DialogueInput fromBody(JsonNode body) {
        List<Turn> history = new ArrayList<>();
        for (JsonNode node : body.path("history")) {
            String role = node.path("role").asText("");
            String text = clip(node.path("text").asText(""));
            if (("CHILD".equals(role) || "CHARACTER".equals(role)) && !text.isBlank()) {
                history.add(new Turn(role, text));
            }
        }
        if (history.size() > MAX_HISTORY_TURNS) {
            history = new ArrayList<>(history.subList(history.size() - MAX_HISTORY_TURNS, history.size()));
        }

        JsonNode sceneNode = body.path("scene");
        Scene scene = null;
        if (sceneNode.isObject()) {
            scene = new Scene(
                    clip(sceneNode.path("title").asText("")),
                    texts(sceneNode.path("storySoFar"), 12),
                    texts(sceneNode.path("recentLines"), 8),
                    clip(sceneNode.path("visual").asText("")));
        }

        String anchorId = body.path("anchorId").isTextual() && !body.path("anchorId").asText().isBlank()
                ? body.path("anchorId").asText().trim()
                : null;
        String wrapUp = body.path("wrapUp").asText("NONE");
        return new DialogueInput(
                List.copyOf(history), scene, texts(body.path("executedActions"), 6), anchorId,
                WRAP_UPS.contains(wrapUp) ? wrapUp : "NONE");
    }

    private static List<String> texts(JsonNode array, int max) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : array) {
            String text = clip(node.asText(""));
            if (!text.isBlank()) values.add(text);
        }
        return values.size() > max ? List.copyOf(values.subList(values.size() - max, values.size())) : List.copyOf(values);
    }

    private static String clip(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() > MAX_TEXT ? trimmed.substring(0, MAX_TEXT) : trimmed;
    }
}

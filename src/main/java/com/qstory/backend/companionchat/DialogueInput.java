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
 * @param help          아이가 "도와줘"를 눌렀을 때만 - 지금 도움 단계와 그 단계의 미리 쓴 도움 대사(방향).
 *                      그레텔은 이 방향을 따르되 앞 대화에서 이미 한 말은 되풀이하지 않고 대화에 맞춰 말한다.
 * @param attempt       질문 초대의 시도 장면 상태(클라이언트가 들고 있다) - 이 질문 지점에서 이미 만든 시도 장면 수와,
 *                      지금 시도 장면 안이면 그 장면, 이 질문 지점의 시도 장면 배경 그림 후보. 없으면 시도 장면을 한 번도 안 한 것이다.
 */
public record DialogueInput(
        List<Turn> history,
        Scene scene,
        List<String> executedActions,
        String anchorId,
        String wrapUp,
        Help help,
        Attempt attempt,
        com.qstory.backend.extension.learning.LearningState learning) {

    /**
     * @param tried     이 질문 지점에서 이미 만든 시도 장면 수
     * @param active    지금 시도 장면 안이면 그 장면, 아니면 null
     * @param backdrops 이 질문 지점에 준비된 시도 장면 배경 그림 후보(콘텐츠 story.yaml attemptBackdrops). 새 시도 장면을
     *                  만들 때 막힌 모습과 같은 그림을 하나 고르는 데만 쓴다 - 그림을 보여 주는 것 말고는 아무것도 바꾸지 않는다.
     */
    public record Attempt(int tried, Active active, List<Backdrop> backdrops) {
        public static final Attempt NONE = new Attempt(0, null);

        public Attempt {
            backdrops = backdrops == null ? List.of() : List.copyOf(backdrops);
        }

        public Attempt(int tried, Active active) {
            this(tried, active, List.of());
        }
    }

    /**
     * 시도 장면 배경 그림 후보 하나.
     *
     * @param id    그림 asset id(콘텐츠 assets.json의 slug)
     * @param shows 그림에 무엇이 막혀 있는지 짧게(예: 빽빽한 덤불을 두 손으로 헤쳐 보지만 더 못 감)
     */
    public record Backdrop(String id, String shows) {}

    /** 배경 후보 수·설명 길이 상한 - 프롬프트와 스키마 enum에 그대로 들어가므로 작게 둔다. */
    public static final int MAX_BACKDROPS = 8;
    public static final int MAX_BACKDROP_TEXT = 120;
    private static final java.util.regex.Pattern BACKDROP_ID = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    /**
     * @param tried 아이가 해 보자고 한 것(뜻)
     * @param scene 시도 장면 글(내레이터·인물 줄과 그레텔이 물은 말)
     * @param turn  이번 아이 말이 장면 안에서 몇 번째인지(1부터)
     */
    public record Active(String tried, String scene, int turn) {}

    public DialogueInput {
        attempt = attempt == null ? Attempt.NONE : attempt;
        learning = learning == null ? com.qstory.backend.extension.learning.LearningState.initial("UNKNOWN") : learning;
    }

    // QStory Extension Kit: preserve all pre-existing constructor call sites.
    public DialogueInput(
            List<Turn> history, Scene scene, List<String> executedActions, String anchorId,
            String wrapUp, Help help, Attempt attempt) {
        this(history, scene, executedActions, anchorId, wrapUp, help, attempt, null);
    }

    public DialogueInput(
            List<Turn> history, Scene scene, List<String> executedActions, String anchorId, String wrapUp, Help help) {
        this(history, scene, executedActions, anchorId, wrapUp, help, Attempt.NONE);
    }

    /**
     * @param step  이번에 주는 도움 단계(1부터)
     * @param total 이 질문 지점의 도움 단계 수
     * @param hint  이 단계의 미리 쓴 도움 대사 - 정답을 알려 주지 않는 범위가 이미 지켜진 문장이다
     */
    public record Help(int step, int total, String hint) {}

    public DialogueInput(List<Turn> history, Scene scene, List<String> executedActions, String anchorId, String wrapUp) {
        this(history, scene, executedActions, anchorId, wrapUp, null, Attempt.NONE);
    }

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
        return new DialogueInput(List.of(), null, List.of(), null, "NONE", null, Attempt.NONE);
    }

    public boolean isInvite() {
        return anchorId != null;
    }

    public boolean isHelp() {
        return help != null;
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
                WRAP_UPS.contains(wrapUp) ? wrapUp : "NONE", help(body.path("help")), attempt(body.path("attempt")),
                com.qstory.backend.extension.learning.StoryPalAddon.readState(body.path("learning")));
    }

    /** 시도 장면 상태 - 형식이 어긋나면 시도 장면이 없는 것으로 본다(장면을 지어내지 않는다). */
    private static Attempt attempt(JsonNode node) {
        if (!node.isObject()) return Attempt.NONE;
        int tried = Math.max(0, Math.min(node.path("tried").asInt(0), 20));
        JsonNode active = node.path("active");
        Active current = null;
        if (active.isObject()) {
            String what = clip(active.path("tried").asText(""));
            String scene = clip(active.path("scene").asText(""), 600);
            int turn = active.path("turn").asInt(0);
            if (!what.isBlank() && !scene.isBlank() && turn >= 1 && turn <= 20) {
                current = new Active(what, scene, turn);
            }
        }
        return new Attempt(tried, current, backdrops(node.path("backdrops")));
    }

    /** 배경 후보 - id 형식이 어긋나거나 설명이 없거나 겹치는 항목은 버린다. "NONE"은 "맞는 그림 없음"이라 id로 받지 않는다. */
    private static List<Backdrop> backdrops(JsonNode array) {
        List<Backdrop> values = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonNode node : array) {
            String id = node.path("id").asText("").trim();
            String shows = clip(node.path("description").asText(""), MAX_BACKDROP_TEXT);
            if (!BACKDROP_ID.matcher(id).matches() || shows.isBlank() || !seen.add(id)) continue;
            values.add(new Backdrop(id, shows));
            if (values.size() == MAX_BACKDROPS) break;
        }
        return List.copyOf(values);
    }

    /** 도움 요청은 질문 초대 안에서만 의미가 있다 - 단계·대사가 어긋나면 도움 요청이 아닌 것으로 본다. */
    private static Help help(JsonNode node) {
        if (!node.isObject()) return null;
        int step = node.path("step").asInt(0);
        int total = node.path("total").asInt(0);
        String hint = clip(node.path("hint").asText(""));
        if (step < 1 || total < step || total > 10 || hint.isBlank()) return null;
        return new Help(step, total, hint);
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
        return clip(value, MAX_TEXT);
    }

    private static String clip(String value, int max) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}

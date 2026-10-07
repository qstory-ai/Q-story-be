package com.qstory.backend.reportanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 분석 프롬프트에 넣을 이야기 맥락 - GET /v1/stories/{id}/content와 같은 꾸러미({packageData, generatedContent})에서
 * 장면 순서·제목·줄거리, 질문 지점(초대 대사·준비된 행동)만 뽑는다. 아이가 읽은 장면까지만 넘긴다.
 */
final class StoryView {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String title;
    private final List<String> sceneOrder;
    private final Map<String, String> sceneTitles;
    private final JsonNode synopses;
    private final JsonNode anchors;
    private final Map<String, String> inviteLines;
    private final Map<String, String> familyMeanings;

    private StoryView(String title, List<String> sceneOrder, Map<String, String> sceneTitles, JsonNode synopses,
            JsonNode anchors, Map<String, String> inviteLines, Map<String, String> familyMeanings) {
        this.title = title;
        this.sceneOrder = sceneOrder;
        this.sceneTitles = sceneTitles;
        this.synopses = synopses;
        this.anchors = anchors;
        this.inviteLines = inviteLines;
        this.familyMeanings = familyMeanings;
    }

    static StoryView of(JsonNode content) {
        JsonNode packageData = content.path("packageData");
        JsonNode story = packageData.path("story");
        List<String> order = new ArrayList<>();
        Map<String, String> titles = new LinkedHashMap<>();
        Map<String, String> invites = new LinkedHashMap<>();
        for (JsonNode scene : content.path("generatedContent").path("scenes")) {
            String id = scene.path("id").asText();
            order.add(id);
            titles.put(id, scene.path("title").asText());
            for (JsonNode segment : scene.path("segments")) {
                String role = segment.path("role").asText("");
                if (role.startsWith("QUESTION_INVITE:")) {
                    invites.put(id, segment.path("text").asText().replace("“", "").replace("”", "").trim());
                }
            }
        }
        JsonNode anchors = packageData.path("routeContext").path("anchors");
        Map<String, String> meanings = new LinkedHashMap<>();
        anchors.fields().forEachRemaining(anchor -> {
            for (JsonNode family : anchor.getValue().path("actionFamilies")) {
                meanings.put(family.path("id").asText(), family.path("meaning").asText());
            }
        });
        return new StoryView(story.path("title").asText(), order, titles, story.path("sceneSynopses"), anchors, invites, meanings);
    }

    /**
     * 읽은 장면 - 저장된 읽은 범위가 있으면 그 범위, 없으면 대화가 있었던 장면까지(처음 장면부터). 둘 다 없으면 처음 장면 하나.
     */
    List<String> readScenes(String fromSceneId, String throughSceneId, List<Map<String, Object>> turns) {
        int from = Math.max(0, fromSceneId == null ? 0 : sceneOrder.indexOf(fromSceneId));
        int through = throughSceneId == null ? -1 : sceneOrder.indexOf(throughSceneId);
        if (through < 0) {
            for (Map<String, Object> turn : turns) {
                through = Math.max(through, sceneOrder.indexOf(String.valueOf(turn.get("sceneId"))));
            }
        }
        if (through < 0 || sceneOrder.isEmpty()) return sceneOrder.isEmpty() ? List.of() : List.of(sceneOrder.get(0));
        return new ArrayList<>(sceneOrder.subList(Math.min(from, through), through + 1));
    }

    /** 질문 지점이 있는 장면(장면 순서대로). */
    List<String> anchorScenes() {
        List<String> scenes = new ArrayList<>();
        anchors.fields().forEachRemaining(anchor -> scenes.add(anchor.getValue().path("sceneId").asText()));
        scenes.sort((a, b) -> Integer.compare(sceneOrder.indexOf(a), sceneOrder.indexOf(b)));
        return scenes;
    }

    String familyMeaning(String familyId) {
        return familyMeanings.getOrDefault(familyId, familyId);
    }

    ObjectNode context(List<String> readScenes) {
        ObjectNode context = JSON.createObjectNode();
        context.put("title", title);
        ArrayNode scenes = context.putArray("readScenes");
        for (String id : readScenes) {
            ObjectNode scene = scenes.addObject();
            scene.put("id", id);
            scene.put("title", sceneTitles.getOrDefault(id, id));
            scene.put("synopsis", synopses.path(id).asText(""));
        }
        ArrayNode points = context.putArray("questionPoints");
        anchors.fields().forEachRemaining(anchor -> {
            String sceneId = anchor.getValue().path("sceneId").asText();
            if (!readScenes.contains(sceneId)) return;
            ObjectNode point = points.addObject();
            point.put("id", anchor.getKey());
            point.put("sceneId", sceneId);
            point.put("invite", inviteLines.getOrDefault(sceneId, ""));
            point.put("summary", anchor.getValue().path("summary").asText(""));
            ArrayNode actions = point.putArray("preparedActions");
            for (JsonNode family : anchor.getValue().path("actionFamilies")) {
                actions.addObject().put("id", family.path("id").asText()).put("meaning", family.path("meaning").asText());
            }
        });
        return context;
    }
}

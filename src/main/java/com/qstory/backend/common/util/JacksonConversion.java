package com.qstory.backend.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * StoryImportService/ShadowFamilyGenerationService/StoryRevisionService/StoryAuthoringService가
 * 각자 손으로 다시 작성했던 "JsonNode/엔티티를 Map으로 변환한다" 로직을 하나로 모았다. 호출부마다
 * ObjectMapper 인스턴스가 다르므로 static 메서드가 그것을 받는다.
 */
public final class JacksonConversion {

    private JacksonConversion() {}

    /** node가 없거나 null이면 null - StoryImportService의 optional 섹션(reportCopy/release/... )이 이 형태다. */
    public static Map<String, Object> toMap(ObjectMapper objectMapper, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    public static List<Map<String, Object>> toListOfMaps(ObjectMapper objectMapper, JsonNode arrayNode) {
        List<Map<String, Object>> list = new ArrayList<>();
        arrayNode.forEach(item -> list.add(toMap(objectMapper, item)));
        return list;
    }

    /** 엔티티/DTO를 스냅샷으로 변환할 때 쓴다 - StoryRevisionService.snapshot()/StoryAuthoringService.asMap() 용도. */
    public static Map<String, Object> toMap(ObjectMapper objectMapper, Object value) {
        return value == null ? null : objectMapper.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
    }
}

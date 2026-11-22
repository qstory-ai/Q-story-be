package com.qstory.backend.reportanalysis.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 아이 말 분류(질문 여부·수준·종류)를 회차 하나의 숫자로 묶는다. 부모 리포트의 "오늘 한 줄"과 "지난번과 달라진 점"이
 * 이 숫자를 쓴다. 지난 회차와 비교할 때도 LLM이 세지 않게 여기서 미리 센다.
 *
 * <p>수준은 Glaubman 4단계다. 1 예/아니오, 2 사실(누가·무엇·어디), 3 이유·방법(왜·어떻게), 4 가설·예측(만약·앞으로).
 * 부모 화면에는 숫자 대신 "왜·어떻게를 물은 질문"처럼 말로 푼다(whyHow = 3·4단계 수).
 */
final class QuestionStats {

    static final List<String> CATEGORIES = List.of("NARRATIVE", "FACT_CAUSE", "MIND_RELATION");

    private QuestionStats() {}

    /** questions: classify 결과({kind, level, category, followUp, topic}). 질문이 없어도 0으로 채운 요약을 돌려준다. */
    static Map<String, Object> summarize(List<Map<String, Object>> questions) {
        int asked = 0;
        int whyHow = 0;
        int followUps = 0;
        int proposals = 0;
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        for (String category : CATEGORIES) byCategory.put(category, 0);
        Set<String> topics = new LinkedHashSet<>();
        for (Map<String, Object> q : questions) {
            String kind = String.valueOf(q.get("kind"));
            if ("PROPOSAL".equals(kind)) proposals++;
            if (!"QUESTION".equals(kind)) continue;
            asked++;
            if (level(q) >= 3) whyHow++;
            if (Boolean.TRUE.equals(q.get("followUp"))) followUps++;
            byCategory.computeIfPresent(String.valueOf(q.get("category")), (k, v) -> v + 1);
            Object topic = q.get("topic");
            if (topic instanceof String t && !t.isBlank()) topics.add(t.trim());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("questions", asked);
        out.put("whyHow", whyHow);
        out.put("followUps", followUps);
        out.put("proposals", proposals);
        out.put("byCategory", byCategory);
        out.put("topics", new ArrayList<>(topics));
        return out;
    }

    static int level(Map<String, Object> question) {
        return question.get("level") instanceof Number n ? n.intValue() : 0;
    }
}

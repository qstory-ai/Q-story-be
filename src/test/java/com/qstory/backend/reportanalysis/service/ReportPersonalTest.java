package com.qstory.backend.reportanalysis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 질문 분류 정리, 회차 숫자 세기, 개인화 문장의 코드 규칙(보고서 개편 v1). */
class ReportPersonalTest {

    private final ObjectMapper json = new ObjectMapper();

    private Map<String, Map<String, Object>> turns() {
        Map<String, Map<String, Object>> turns = new LinkedHashMap<>();
        turns.put("t1", Map.of("seq", 1, "role", "CHARACTER", "text", "저 집 좀 봐!"));
        turns.put("t2", Map.of("seq", 2, "role", "CHILD", "text", "왜 과자로 만들었어?"));
        turns.put("t3", Map.of("seq", 3, "role", "CHILD", "text", "문 열어 볼까?"));
        turns.put("t4", Map.of("seq", 4, "role", "CHILD", "text", "응"));
        return turns;
    }

    private List<Map<String, Object>> classified() throws Exception {
        var raw = json.readTree("""
                {"items":[
                  {"turnId":"t2","kind":"QUESTION","level":3,"category":"FACT_CAUSE","followUp":false,"topic":"과자집","reason":"왜"},
                  {"turnId":"t3","kind":"PROPOSAL","level":2,"category":"NARRATIVE","followUp":true,"topic":"문","reason":"제안"},
                  {"turnId":"t4","kind":"OTHER","level":0,"category":"NONE","followUp":false,"topic":"","reason":"대답"},
                  {"turnId":"t2","kind":"OTHER","level":0,"category":"NONE","followUp":false,"topic":"","reason":"중복"},
                  {"turnId":"t1","kind":"QUESTION","level":1,"category":"NARRATIVE","followUp":false,"topic":"집","reason":"캐릭터 말"}
                ]}""");
        return ReportAnalysisService.classifiedOut(raw, List.of("t2", "t3", "t4"));
    }

    @Test
    void classificationKeepsOnlyChildTurnsOnceAndClearsNonQuestions() throws Exception {
        List<Map<String, Object>> out = classified();
        assertEquals(3, out.size());
        assertEquals(List.of(2, 3, 4), out.stream().map(q -> q.get("seq")).toList());
        assertEquals("QUESTION", out.get(0).get("kind"));
        assertEquals(3, out.get(0).get("level"));
        // 제안은 질문이 아니라 수준·종류·후속 여부를 비운다.
        assertEquals(0, out.get(1).get("level"));
        assertNull(out.get(1).get("category"));
        assertEquals(false, out.get(1).get("followUp"));
        assertEquals("", out.get(1).get("topic"));
    }

    @Test
    void summaryCountsWhyHowAndCategories() throws Exception {
        Map<String, Object> summary = QuestionStats.summarize(classified());
        assertEquals(1, summary.get("questions"));
        assertEquals(1, summary.get("whyHow"));
        assertEquals(1, summary.get("proposals"));
        assertEquals(0, summary.get("followUps"));
        assertEquals(Map.of("NARRATIVE", 0, "FACT_CAUSE", 1, "MIND_RELATION", 0), summary.get("byCategory"));
        assertEquals(List.of("과자집"), summary.get("topics"));
    }

    @Test
    void emptySessionStillHasZeroSummary() {
        Map<String, Object> summary = QuestionStats.summarize(List.of());
        assertEquals(0, summary.get("questions"));
        assertEquals(List.of(), summary.get("topics"));
    }

    @Test
    void comparisonLinesAreDroppedWithoutHistory() throws Exception {
        var generated = json.readTree("""
                {"todayLine":"하은이는 오늘 질문을 1번 했어요.","changeLine":"지난번보다 많이 물었어요.",
                 "interestLine":"과자집을 자주 물었어요.","tonightQuestion":{"basedOnTurnId":"t2","text":"너는 왜 과자로 만든 것 같아?"}}""");
        Map<String, Object> first = ReportAnalysisService.personalOut(generated, classified(), turns(), false);
        assertNull(first.get("changeLine"));
        assertNull(first.get("interestLine"));
        assertEquals("하은이는 오늘 질문을 1번 했어요.", first.get("todayLine"));

        Map<String, Object> later = ReportAnalysisService.personalOut(generated, classified(), turns(), true);
        assertEquals("지난번보다 많이 물었어요.", later.get("changeLine"));
    }

    @Test
    void tonightQuestionMustComeFromARealQuestion() throws Exception {
        var fromQuestion = json.readTree("""
                {"todayLine":"x","changeLine":null,"interestLine":null,
                 "tonightQuestion":{"basedOnTurnId":"t2","text":"너는 왜 과자로 만든 것 같아?"}}""");
        @SuppressWarnings("unchecked")
        Map<String, Object> tonight = (Map<String, Object>) ReportAnalysisService
                .personalOut(fromQuestion, classified(), turns(), false).get("tonightQuestion");
        assertEquals(2, tonight.get("seq"));
        assertEquals("왜 과자로 만들었어?", tonight.get("childQuestion"));

        // 제안(t3)이나 캐릭터 말(t1)에 붙은 질문은 버린다.
        for (String turnId : List.of("t3", "t1", "t9")) {
            var wrong = json.readTree("{\"todayLine\":\"x\",\"changeLine\":null,\"interestLine\":null,"
                    + "\"tonightQuestion\":{\"basedOnTurnId\":\"" + turnId + "\",\"text\":\"문을 열면 어떻게 될까?\"}}");
            assertNull(ReportAnalysisService.personalOut(wrong, classified(), turns(), false).get("tonightQuestion"));
        }
    }

    @Test
    void personalTextIsCheckedButTheChildNameIsAllowed() throws Exception {
        var latinName = json.readTree("{\"todayLine\":\"Olivia는 오늘 두 번 물었어요.\"}");
        assertTrue(ReportAnalysisService.personalProblems(latinName, "Olivia").isEmpty());

        var judged = json.readTree("{\"changeLine\":\"질문 실력이 향상됐어요.\"}");
        assertEquals(List.of("성향·능력 일반화 표현"), ReportAnalysisService.personalProblems(judged, "하은"));
    }
}

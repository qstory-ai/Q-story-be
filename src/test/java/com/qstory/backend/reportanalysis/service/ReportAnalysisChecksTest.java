package com.qstory.backend.reportanalysis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 분석 근거 확인(코드 쪽) - 근거는 실제 개인 아이 말만, 성향 일반화·외국어 섞임은 막는다. */
class ReportAnalysisChecksTest {

    private final ObjectMapper json = new ObjectMapper();

    private Map<String, Map<String, Object>> turns() {
        Map<String, Map<String, Object>> turns = new LinkedHashMap<>();
        turns.put("t1", Map.of("seq", 1, "role", "CHARACTER", "text", "저 새를 보니 궁금한 게 있어?"));
        turns.put("t2", Map.of("seq", 2, "role", "CHILD", "speaker", "UNVERIFIED", "text", "새는 어디 가?"));
        turns.put("t3", Map.of("seq", 3, "role", "CHILD", "speaker", "TEACHER_RELAY", "text", "말 걸어보자"));
        return turns;
    }

    @Test
    void evidenceMustBeThePersonalChildWords() throws Exception {
        var ok = json.readTree("{\"evidenceTurnIds\":[\"t2\"],\"observation\":\"새가 어디로 가는지 궁금해했어요.\"}");
        assertTrue(ReportAnalysisService.codeCheck(ok, turns()).isEmpty());

        var character = json.readTree("{\"evidenceTurnIds\":[\"t1\"],\"observation\":\"...\"}");
        assertEquals(List.of("t1는 아이 발화가 아님"), ReportAnalysisService.codeCheck(character, turns()));

        var relay = json.readTree("{\"evidenceTurnIds\":[\"t3\"],\"observation\":\"...\"}");
        assertEquals(List.of("t3는 개인 발화가 아님"), ReportAnalysisService.codeCheck(relay, turns()));

        var missing = json.readTree("{\"evidenceTurnIds\":[\"t9\"],\"observation\":\"...\"}");
        assertEquals(List.of("없는 발화 t9"), ReportAnalysisService.codeCheck(missing, turns()));
    }

    @Test
    void traitGeneralisationIsRejected() throws Exception {
        var trait = json.readTree("{\"evidenceTurnIds\":[\"t2\"],\"observation\":\"동물을 좋아하는 성향이 있어요.\"}");
        assertEquals(List.of("성향·능력 일반화 표현"), ReportAnalysisService.codeCheck(trait, turns()));
    }

    @Test
    void generatedTextIsCheckedForForeignWordsButNotKeysOrTypes() throws Exception {
        var clean = json.readTree("{\"cards\":[{\"key\":\"o1\",\"headline\":\"새가 가는 곳을 물었어요.\","
                + "\"followUps\":[{\"type\":\"LOOK_TOGETHER\",\"text\":\"새를 같이 볼까?\"}]}]}");
        assertTrue(ReportAnalysisService.textProblems(clean).isEmpty());

        var mixed = json.readTree("{\"cards\":[{\"key\":\"o1\",\"acknowledge\":\"naprawdę 많이 놀랐겠다\"}]}");
        assertEquals(List.of("외국어 섞임: naprawdę"), ReportAnalysisService.textProblems(mixed));

        var praise = json.readTree("{\"cards\":[{\"explanation\":\"지혜로운 의견을 냈어요.\"}]}");
        assertEquals(List.of("성향·능력 일반화 표현"), ReportAnalysisService.textProblems(praise));
    }
}

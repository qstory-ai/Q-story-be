package com.qstory.backend.provider.openrouter.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.choicecopy.service.ChoiceCopyService;
import com.qstory.backend.provider.openrouter.ContentGeneration;
import com.qstory.backend.provider.openrouter.RouteClassification;
import com.qstory.backend.provider.openrouter.RouteOption;
import com.qstory.backend.provider.openrouter.SafetyVerdict;
import com.qstory.backend.story.ActionFamily;
import com.qstory.backend.story.StoryContext;
import com.qstory.backend.story.StoryVersions;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 2의 3단계 파이프라인(safety_scope_gate/route_classifier/content_generator) 검증 메서드
 * 단위테스트. 폐기된 alignActionRouteCoverage/promoteConcernToChoice에는 이 코드베이스에 기존
 * 테스트가 없었으므로(StoryImportServiceTest 하나뿐 - 확인됨) 옮겨올 기존 assertion은 없다. 대신
 * 그 두 메서드를 대체하는 새 판정 지점(NEW_CHOICES 검증, coverageStatus 필드 규칙)을 여기서 검증한다.
 */
class RouteResultValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RouteResultValidator validator = new RouteResultValidator(mock(ChoiceCopyService.class));

    private StoryContext storyContext() {
        ActionFamily familyA = new ActionFamily("A_FAMILY", "A 의미", "좋아, A 할게.", "A 보고", "a-bridge", "a-asset", List.of());
        ActionFamily familyB = new ActionFamily("B_FAMILY", "B 의미", "좋아, B 할게.", "B 보고", "b-bridge", "b-asset", List.of());
        ActionFamily familyC = new ActionFamily("C_FAMILY", "C 의미", "좋아, C 할게.", "C 보고", "c-bridge", "c-asset", List.of());
        return new StoryContext(
                "A", "HG-F04", "장면 요약", "HG-SPK-GRETEL", List.of("HG-SPK-GRETEL"), List.of(),
                "A_FAMILY", "HG-F04-REJOIN", null, List.of(), List.of(familyA, familyB, familyC),
                "HG-Q-A", "HG", "A_FAMILY", "HG-F04-REJOIN",
                new StoryVersions("QSTORY_ROUTE_PROMPT_V6_COVERAGE", "v1", "v1", "v1"));
    }

    private JsonNode json(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    @Test
    void safetyVerdictPassRequiresNullReasonAndText() {
        SafetyVerdict verdict = validator.validateSafetyVerdict(
                json("{\"verdict\":\"PASS\",\"redirectReason\":null,\"responseText\":null}"), "safety-model");
        assertThat(verdict).isNotNull();
        assertThat(verdict.isRedirect()).isFalse();
        assertThat(verdict.redirectReason()).isNull();
        assertThat(verdict.responseText()).isNull();
    }

    @Test
    void safetyVerdictRedirectRequiresReasonAndText() {
        SafetyVerdict missing = validator.validateSafetyVerdict(
                json("{\"verdict\":\"REDIRECT\",\"redirectReason\":null,\"responseText\":null}"), "safety-model");
        assertThat(missing).isNull();

        SafetyVerdict valid = validator.validateSafetyVerdict(
                json("{\"verdict\":\"REDIRECT\",\"redirectReason\":\"위험한 행동 제안\",\"responseText\":\"그건 위험해서 안 돼.\"}"),
                "safety-model");
        assertThat(valid).isNotNull();
        assertThat(valid.isRedirect()).isTrue();
        assertThat(valid.redirectReason()).isEqualTo("위험한 행동 제안");
    }

    @Test
    void classificationRejectsGentleRedirectRoute() {
        // 안전 판정은 1단계 전담이라 분류기가 GENTLE_REDIRECT를 반환하면 무조건 거부되어야 한다.
        RouteClassification classification = validator.validateClassification(
                json("""
                {"route":"GENTLE_REDIRECT","matchedGate":"G1","coverageStatus":"exact","coverageReason":"사유",
                 "childRelevantMeaning":"의미","speakerId":"HG-SPK-GRETEL","actionFamilyId":null,
                 "rejoinAnchorId":null,"fallbackFamilyId":null}
                """),
                storyContext(), "model");
        assertThat(classification).isNull();
    }

    @Test
    void classificationNewChoicesRequiresAllFamilyFieldsNull() {
        StoryContext ctx = storyContext();
        RouteClassification withFamily = validator.validateClassification(
                json("""
                {"route":"NEW_CHOICES","matchedGate":"NEW","coverageStatus":"uncovered","coverageReason":"사유",
                 "childRelevantMeaning":"의미","speakerId":"HG-SPK-GRETEL","actionFamilyId":"A_FAMILY",
                 "rejoinAnchorId":null,"fallbackFamilyId":null}
                """),
                ctx, "model");
        assertThat(withFamily).isNull();

        RouteClassification valid = validator.validateClassification(
                json("""
                {"route":"NEW_CHOICES","matchedGate":"NEW","coverageStatus":"uncovered","coverageReason":"사유",
                 "childRelevantMeaning":"의미","speakerId":"HG-SPK-GRETEL","actionFamilyId":null,
                 "rejoinAnchorId":null,"fallbackFamilyId":null}
                """),
                ctx, "model");
        assertThat(valid).isNotNull();
        assertThat(valid.route()).isEqualTo("NEW_CHOICES");
        assertThat(valid.actionFamilyId()).isNull();
    }

    @Test
    void classificationActionRouteRequiresFixedRejoinAndFallback() {
        StoryContext ctx = storyContext();
        RouteClassification wrongRejoin = validator.validateClassification(
                json("""
                {"route":"DIRECT_ACTION","matchedGate":"G7","coverageStatus":"exact","coverageReason":"사유",
                 "childRelevantMeaning":"의미","speakerId":"HG-SPK-GRETEL","actionFamilyId":"A_FAMILY",
                 "rejoinAnchorId":"WRONG-ANCHOR","fallbackFamilyId":"A_FAMILY"}
                """),
                ctx, "model");
        assertThat(wrongRejoin).isNull();

        RouteClassification valid = validator.validateClassification(
                json("""
                {"route":"DIRECT_ACTION","matchedGate":"G7","coverageStatus":"exact","coverageReason":"사유",
                 "childRelevantMeaning":"의미","speakerId":"HG-SPK-GRETEL","actionFamilyId":"A_FAMILY",
                 "rejoinAnchorId":"HG-F04-REJOIN","fallbackFamilyId":"A_FAMILY"}
                """),
                ctx, "model");
        assertThat(valid).isNotNull();
        assertThat(valid.actionFamilyId()).isEqualTo("A_FAMILY");
    }

    @Test
    void contentRejectsWrongOptionCountForThreePaths() {
        StoryContext ctx = storyContext();
        // optionSlots=3인데 옵션이 2개뿐 - 계획 문서가 명시한 "스키마만으로 강제할 수 없는" 케이스.
        ContentGeneration tooFew = validator.validateContent(
                json("""
                {"responseText":"골라보자.","options":[
                  {"id":"OPTION_1","label":"관찰하기","meaning":"의미1","branchLine":"대사1","actionFamilyId":"A_FAMILY"},
                  {"id":"OPTION_2","label":"말걸기","meaning":"의미2","branchLine":"대사2","actionFamilyId":"B_FAMILY"}
                ]}
                """),
                ctx, "THREE_PATHS", 3);
        assertThat(tooFew).isNull();

        ContentGeneration valid = validator.validateContent(
                json("""
                {"responseText":"골라보자.","options":[
                  {"id":"OPTION_1","label":"관찰하기","meaning":"의미1","branchLine":"대사1","actionFamilyId":"A_FAMILY"},
                  {"id":"OPTION_2","label":"말걸기","meaning":"의미2","branchLine":"대사2","actionFamilyId":"B_FAMILY"},
                  {"id":"OPTION_3","label":"행동하기","meaning":"의미3","branchLine":"대사3","actionFamilyId":"C_FAMILY"}
                ]}
                """),
                ctx, "THREE_PATHS", 3);
        assertThat(valid).isNotNull();
        assertThat(valid.options()).hasSize(3);
    }

    @Test
    void contentRejectsNonEmptyOptionsForZeroSlotRoutes() {
        StoryContext ctx = storyContext();
        ContentGeneration withOptions = validator.validateContent(
                json("""
                {"responseText":"괜찮아, 이야기를 계속 들어보자.","options":[
                  {"id":"OPTION_1","label":"관찰하기","meaning":"의미1","branchLine":"대사1","actionFamilyId":"A_FAMILY"}
                ]}
                """),
                ctx, "SKIP_CONTINUE", 0);
        assertThat(withOptions).isNull();

        ContentGeneration valid = validator.validateContent(
                json("{\"responseText\":\"괜찮아, 이야기를 계속 들어보자.\",\"options\":[]}"),
                ctx, "SKIP_CONTINUE", 0);
        assertThat(valid).isNotNull();
        assertThat(valid.options()).isEmpty();
    }

    @Test
    void contentRejectsDuplicateActionFamilyIdsAcrossOptions() {
        StoryContext ctx = storyContext();
        ContentGeneration duplicated = validator.validateContent(
                json("""
                {"responseText":"골라보자.","options":[
                  {"id":"OPTION_1","label":"관찰하기","meaning":"의미1","branchLine":"대사1","actionFamilyId":"A_FAMILY"},
                  {"id":"OPTION_2","label":"말걸기","meaning":"의미2","branchLine":"대사2","actionFamilyId":"A_FAMILY"},
                  {"id":"OPTION_3","label":"행동하기","meaning":"의미3","branchLine":"대사3","actionFamilyId":"C_FAMILY"}
                ]}
                """),
                ctx, "THREE_PATHS", 3);
        assertThat(duplicated).isNull();
    }

    @Test
    void guaranteeBetaAgencyChoiceIgnoresNonAnswerResumeRoute() {
        StoryContext ctx = storyContext();
        var decision = new com.qstory.backend.provider.openrouter.RouteDecision(
                "SKIP_CONTINUE", "의미", "exact", "사유", "괜찮아, 이야기를 계속 들어보자.", "HG-SPK-GRETEL",
                null, null, null, List.<RouteOption>of(), "model", ctx.versions(), null, false);
        var result = validator.guaranteeBetaAgencyChoice(decision, ctx, "transcript", true, 1);
        assertThat(result).isSameAs(decision);
    }
}

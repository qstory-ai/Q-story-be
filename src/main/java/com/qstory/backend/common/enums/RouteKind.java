package com.qstory.backend.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum RouteKind {
    ANSWER_RESUME,
    DIRECT_ACTION,
    THREE_PATHS,
    SCENE_REPLACE,
    DETOUR_REJOIN,
    CLARIFY_ONCE,
    GENTLE_REDIRECT,
    SKIP_CONTINUE,
    /**
     * Phase 2 분류기(route_classifier)만 반환하는 route. "내용과 연관되고 새롭고 맥락상 나올 수 있는"
     * 요청인데 기존 allowedFamilies로는 (완전히) 커버되지 않을 때, THREE_PATHS를 억지로 만들거나
     * uncovered로 그냥 끝내는 대신 고른다 - LiveBranchGenerationService.enqueue()가 최대 3개의 새
     * family를 비동기로 만든 뒤 프런트가 그걸로 THREE_PATHS를 구성한다(QuestionRoutingService 참고).
     */
    NEW_CHOICES;

    /**
     * values()에서 그대로 뽑아낸다 - 예전에는 8개 이름을 손으로 다시 나열해서, 나중에
     * route가 하나 추가돼도 여기 반영을 깜빡하면 그 route는 항상 "유효하지 않음"으로
     * 검증에 걸리는 조용한 버그가 될 수 있었다.
     */
    public static final Set<String> ALL_NAMES =
            Arrays.stream(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    /** 여기 두 집합(SIMPLE_ROUTES/ACTION_ROUTES)은 "전체 목록"이 아니라 편집자가 고른 분류라, values()에서 자동으로 뽑아낼 수 없다. */
    public static final Set<String> SIMPLE_ROUTES =
            Set.of("ANSWER_RESUME", "CLARIFY_ONCE", "GENTLE_REDIRECT", "SKIP_CONTINUE");

    public static final Set<String> ACTION_ROUTES =
            Set.of("DIRECT_ACTION", "SCENE_REPLACE", "DETOUR_REJOIN");

    /**
     * NEW_CHOICES는 SIMPLE_ROUTES(콘텐츠 없음)도 ACTION_ROUTES(고정 단일 family)도 아니다 - family가
     * 아직 존재하지 않고 비동기로 생성되는 중이라는, 이 두 분류 어디에도 들어맞지 않는 세 번째 종류다.
     * 별도 집합으로 두어 검증기가 이 route를 SIMPLE/ACTION 체크에 실수로 걸리게 하지 않고 명시적으로
     * 다루게 한다.
     */
    public static final Set<String> NEW_CONTENT_ROUTES = Set.of("NEW_CHOICES");

    /**
     * route_classifier(2단계)가 실제로 반환할 수 있는 route 집합 - GENTLE_REDIRECT는 제외된다.
     * 안전/범위 판정은 1단계 safety_scope_gate가 전담하며, 분류기는 "이미 PASS된 발화"만 받으므로
     * 안전 문제를 다시 판정(re-litigate)하지 않는다(QuestionRoutingService 참고).
     */
    public static final Set<String> CLASSIFIER_ROUTES = ALL_NAMES.stream()
            .filter(name -> !name.equals("GENTLE_REDIRECT"))
            .collect(Collectors.toUnmodifiableSet());
}

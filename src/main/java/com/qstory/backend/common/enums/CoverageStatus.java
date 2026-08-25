package com.qstory.backend.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * enum으로 유지함(StoryAvailability/CastRole/TrafficType과는 다르게): 이 어휘 집합은 단순한 개방형
 * 콘텐츠 태그가 아니라 진짜로 구조적이다 - LLM JSON 스키마 구성과 route-result 검증을 구동한다.
 * {@link #wireValue()}/{@link #WIRE_VALUES}가 존재하는 이유는, (예전에 RouteResultValidator/
 * BetaEventValidator/OpenRouterClient가 각각 그랬던 것처럼) "exact"/"partial"/"uncovered"를 독립된
 * 리터럴 집합으로 다시 타이핑하는 대신, 모든 호출 지점이 소문자 wire 표현을 여기서 파생시키도록 하기 위함이다.
 */
public enum CoverageStatus {
    EXACT,
    PARTIAL,
    UNCOVERED;

    public String wireValue() {
        return name().toLowerCase();
    }

    public static final Set<String> WIRE_VALUES =
            Arrays.stream(values()).map(CoverageStatus::wireValue).collect(Collectors.toUnmodifiableSet());
}

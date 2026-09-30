package com.qstory.backend.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * enum으로 유지함(StoryAvailability/CastRole/TrafficType과는 다르게): 이 어휘 집합은 단순한 개방형
 * 콘텐츠 태그가 아니라 진짜로 구조적이다 - LLM JSON 스키마 구성과 route-result 검증을 구동한다.
 * 소문자 wire 표현("exact"/"partial"/"uncovered")은 호출부마다 리터럴로 다시 쓰지 말고
 * {@link #wireValue()}/{@link #WIRE_VALUES}에서 파생시킨다.
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

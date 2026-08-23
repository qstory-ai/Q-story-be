package com.qstory.backend.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Kept as an enum (unlike StoryAvailability/CastRole/TrafficType): this vocabulary is genuinely
 * structural - it drives LLM JSON-schema construction and route-result validation, not just an
 * open content tag. {@link #wireValue()}/{@link #WIRE_VALUES} exist so every call site derives
 * the lowercase wire representation from here instead of re-typing "exact"/"partial"/"uncovered"
 * as independent literal sets (as RouteResultValidator/BetaEventValidator/OpenRouterClient
 * previously each did).
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

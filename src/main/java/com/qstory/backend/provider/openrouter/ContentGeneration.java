package com.qstory.backend.provider.openrouter;

import java.util.List;

/**
 * 3단계 content_generator의 검증된 출력. options는 THREE_PATHS일 때만 정확히 optionSlots(3)개이고,
 * 그 외 모든 route는 반드시 빈 리스트다(RouteResultValidator.validateContent 참고).
 */
public record ContentGeneration(String responseText, List<RouteOption> options) {}

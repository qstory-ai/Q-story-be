package com.qstory.backend.common.enums;

/**
 * Phase 2의 3단계 프롬프트 파이프라인(RoutePromptService.requireStage 참고) 중 하나를 가리킨다.
 * SAFETY(안전게이트) -> CLASSIFIER(라우트 분류기) -> GENERATOR(콘텐츠 생성기) 순서로 호출된다
 * (QuestionRoutingService 참고).
 */
public enum RoutePromptStageKind {
    SAFETY,
    CLASSIFIER,
    GENERATOR
}

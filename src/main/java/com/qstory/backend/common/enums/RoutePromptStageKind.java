package com.qstory.backend.common.enums;

/**
 * 라우팅 프롬프트 YAML의 단계(RoutePromptService.requireStage 참고). 옛 3단계 질문 라우팅이 쓰던 구분이고
 * 지금은 스토리 임포트가 그대로 저장만 한다.
 */
public enum RoutePromptStageKind {
    SAFETY,
    CLASSIFIER,
    GENERATOR
}

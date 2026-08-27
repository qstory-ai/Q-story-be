package com.qstory.backend.common.enums;

/**
 * StoryVisualReferencePack.kind - fe/q-story-web의 visual-generation-contract.ts에 있는
 * VisualReferencePack.kind와 1:1로 매핑된다. LiveBranchExecutionWorker.buildImagePrompt()는 현재
 * CHARACTER만 조회한다(StoryAnchor에 아직 location 필드가 없어 LOCATION은 연결하지 않음 - 계획
 * 문서 Phase 2 §4, 열린 질문 참고).
 */
public enum VisualReferenceKind {
    CHARACTER,
    LOCATION,
    PROP,
    STYLE
}

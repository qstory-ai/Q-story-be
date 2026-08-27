package com.qstory.backend.common.enums;

/**
 * StoryActionFamily가 어떻게 생겨났는지 - StoryImportService의 재임포트가 어떤 family를 삭제 후
 * 재생성해도 되는지(AUTHORED만) 판단하는 근거다. LIVE_GENERATED는 LiveBranchExecutionWorker가
 * 사람 검수 없이 직접 커밋한 family로, 재임포트가 절대 건드리면 안 된다.
 */
public enum FamilyOrigin {
    AUTHORED,
    LIVE_GENERATED
}

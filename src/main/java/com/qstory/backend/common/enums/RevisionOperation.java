package com.qstory.backend.common.enums;

/** 저작(authoring) 편집이 대상을 어떻게 변경했는지. story_revision.operation을 그대로 반영한다. */
public enum RevisionOperation {
    CREATE,
    UPDATE,
    DELETE,
    /** 콘텐츠 파이프라인으로부터의 전체 재임포트로, 스토리 전체에 대해 하나의 revision으로 기록된다. */
    IMPORT,
}

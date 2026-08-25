package com.qstory.backend.common.enums;

/** 저작(authoring) 편집이 변경한 대상. story_revision.target_type을 그대로 반영한다. */
public enum RevisionTarget {
    SCENE,
    SEGMENT,
    ANCHOR,
    ACTION_FAMILY,
    ASSET,
    CAST,
    PROMPT,
}

package com.qstory.backend.common.enums;

/** What an authoring edit changed. Mirrors story_revision.target_type. */
public enum RevisionTarget {
    SCENE,
    SEGMENT,
    ANCHOR,
    ACTION_FAMILY,
    ASSET,
    CAST,
    PROMPT,
}

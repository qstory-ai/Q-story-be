package com.qstory.backend.common.enums;

/** How an authoring edit changed its target. Mirrors story_revision.operation. */
public enum RevisionOperation {
    CREATE,
    UPDATE,
    DELETE,
    /** A full re-import from the content pipeline, recorded as one revision for the whole story. */
    IMPORT,
}

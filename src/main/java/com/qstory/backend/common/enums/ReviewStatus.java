package com.qstory.backend.common.enums;

/** Lifecycle of a shadow_intent_candidates row - never auto-advances past READY_FOR_REVIEW without a human. */
public enum ReviewStatus {
    COLLECTING,
    READY_FOR_REVIEW,
    IN_REVIEW,
    APPROVED,
    REJECTED,
    PROMOTED
}

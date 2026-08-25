package com.qstory.backend.common.enums;

/** shadow_intent_candidates 행의 생명주기 - 사람 없이는 READY_FOR_REVIEW 이후 단계로 절대 자동 진행되지 않는다. */
public enum ReviewStatus {
    COLLECTING,
    READY_FOR_REVIEW,
    IN_REVIEW,
    APPROVED,
    REJECTED,
    PROMOTED
}

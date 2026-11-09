package com.qstory.backend.extension.learning;

import java.util.Set;

/** Bounded metadata from the SAME LLM call. Never used to authorize story actions. */
public record LearningFeedback(String responseStatus, String questionType, boolean consolidation) {
    public static final Set<String> STATUSES = Set.of(
            "UNDERSTOOD", "PARTIAL", "NEEDS_SUPPORT", "UNCERTAIN",
            "CHILD_QUESTION", "EXPERIENCE", "END", "NOT_ASSESSED");
    public LearningFeedback {
        responseStatus = STATUSES.contains(responseStatus == null ? "" : responseStatus)
                ? responseStatus : "NOT_ASSESSED";
        questionType = LearningState.CROWD.contains(questionType == null ? "" : questionType)
                ? questionType : "NONE";
        consolidation = consolidation && "UNDERSTOOD".equals(responseStatus);
    }
    public static LearningFeedback none() {
        return new LearningFeedback("NOT_ASSESSED", "NONE", false);
    }
}

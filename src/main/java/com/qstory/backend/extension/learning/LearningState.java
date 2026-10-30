package com.qstory.backend.extension.learning;

import java.util.Set;

/** Session-local conversation state, NOT a diagnosis, score or persistent child profile. */
public record LearningState(
        String ageBand, int difficulty, int hintLevel, int successStreak,
        int turnIndex, String lastQuestionType, String peerPhase) {
    public static final Set<String> AGE_BANDS = Set.of("UNKNOWN", "5-6", "7-9");
    public static final Set<String> CROWD = Set.of("NONE", "COMPLETION", "RECALL", "OPEN_ENDED", "WH", "DISTANCING");
    public static final Set<String> PHASES = Set.of("PROMPT", "EXPAND", "REPEAT", "SUPPORT", "PAUSE");
    public LearningState {
        ageBand = AGE_BANDS.contains(ageBand == null ? "" : ageBand) ? ageBand : "UNKNOWN";
        difficulty = Math.max(1, Math.min(3, difficulty));
        hintLevel = Math.max(0, Math.min(3, hintLevel));
        successStreak = Math.max(0, Math.min(2, successStreak));
        turnIndex = Math.max(0, Math.min(1000, turnIndex));
        lastQuestionType = CROWD.contains(lastQuestionType == null ? "" : lastQuestionType)
                ? lastQuestionType : "NONE";
        peerPhase = PHASES.contains(peerPhase == null ? "" : peerPhase) ? peerPhase : "PROMPT";
    }
    public static LearningState initial(String ageBand) {
        return new LearningState(ageBand, "7-9".equals(ageBand) ? 2 : 1, 0, 0, 0, "NONE", "PROMPT");
    }
}

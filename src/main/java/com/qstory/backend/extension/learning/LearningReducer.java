package com.qstory.backend.extension.learning;

/** Pure deterministic reducer. A single wrong/short answer never labels the child's ability. */
public final class LearningReducer {
    private LearningReducer() {}
    public static LearningState next(LearningState previous, LearningFeedback feedback, boolean protectedTurn) {
        LearningState p = previous == null ? LearningState.initial("UNKNOWN") : previous;
        LearningFeedback f = feedback == null ? LearningFeedback.none() : feedback;
        int turn = Math.min(1000, p.turnIndex() + 1);
        if (protectedTurn) {
            return new LearningState(p.ageBand(), p.difficulty(), p.hintLevel(), p.successStreak(),
                    turn, p.lastQuestionType(), "PAUSE");
        }
        int difficulty = p.difficulty(), hint = p.hintLevel(), streak = p.successStreak();
        String phase = "PROMPT";
        switch (f.responseStatus()) {
            case "UNDERSTOOD" -> {
                hint = 0;
                streak++;
                if (streak >= 2) { difficulty = Math.min(3, difficulty + 1); streak = 0; }
                phase = f.consolidation() && !"REPEAT".equals(p.peerPhase()) ? "REPEAT" : "EXPAND";
            }
            case "PARTIAL", "NEEDS_SUPPORT" -> {
                hint = Math.min(3, hint + 1);
                streak = 0;
                if (hint == 2) difficulty = Math.max(1, difficulty - 1);
                phase = hint == 3 ? "PAUSE" : "SUPPORT";
            }
            case "UNCERTAIN", "END" -> phase = "PAUSE"; // preserve competence state
            case "CHILD_QUESTION", "EXPERIENCE", "NOT_ASSESSED" -> phase = "PROMPT";
            default -> throw new IllegalStateException("Unexpected bounded feedback");
        }
        String kind = "NONE".equals(f.questionType()) ? p.lastQuestionType() : f.questionType();
        return new LearningState(p.ageBand(), difficulty, hint, streak, turn, kind, phase);
    }
}

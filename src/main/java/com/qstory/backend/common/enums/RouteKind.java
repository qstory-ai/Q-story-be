package com.qstory.backend.common.enums;

import java.util.List;
import java.util.Set;

public enum RouteKind {
    ANSWER_RESUME,
    DIRECT_ACTION,
    THREE_PATHS,
    SCENE_REPLACE,
    DETOUR_REJOIN,
    CLARIFY_ONCE,
    GENTLE_REDIRECT,
    SKIP_CONTINUE;

    public static final List<String> ALL_NAMES =
            List.of("ANSWER_RESUME", "DIRECT_ACTION", "THREE_PATHS", "SCENE_REPLACE",
                    "DETOUR_REJOIN", "CLARIFY_ONCE", "GENTLE_REDIRECT", "SKIP_CONTINUE");

    public static final Set<String> SIMPLE_ROUTES =
            Set.of("ANSWER_RESUME", "CLARIFY_ONCE", "GENTLE_REDIRECT", "SKIP_CONTINUE");

    public static final Set<String> ACTION_ROUTES =
            Set.of("DIRECT_ACTION", "SCENE_REPLACE", "DETOUR_REJOIN");
}

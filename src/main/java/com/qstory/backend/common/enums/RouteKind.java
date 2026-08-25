package com.qstory.backend.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum RouteKind {
    ANSWER_RESUME,
    DIRECT_ACTION,
    THREE_PATHS,
    SCENE_REPLACE,
    DETOUR_REJOIN,
    CLARIFY_ONCE,
    GENTLE_REDIRECT,
    SKIP_CONTINUE;

    /**
     * values()에서 그대로 뽑아낸다 - 예전에는 8개 이름을 손으로 다시 나열해서, 나중에
     * route가 하나 추가돼도 여기 반영을 깜빡하면 그 route는 항상 "유효하지 않음"으로
     * 검증에 걸리는 조용한 버그가 될 수 있었다.
     */
    public static final Set<String> ALL_NAMES =
            Arrays.stream(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    /** 여기 두 집합(SIMPLE_ROUTES/ACTION_ROUTES)은 "전체 목록"이 아니라 편집자가 고른 분류라, values()에서 자동으로 뽑아낼 수 없다. */
    public static final Set<String> SIMPLE_ROUTES =
            Set.of("ANSWER_RESUME", "CLARIFY_ONCE", "GENTLE_REDIRECT", "SKIP_CONTINUE");

    public static final Set<String> ACTION_ROUTES =
            Set.of("DIRECT_ACTION", "SCENE_REPLACE", "DETOUR_REJOIN");
}

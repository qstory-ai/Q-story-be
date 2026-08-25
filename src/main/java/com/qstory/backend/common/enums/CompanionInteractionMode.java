package com.qstory.backend.common.enums;

import java.util.List;

/**
 * 컴패니언 챗 자체의 아주 작은 결과 어휘 집합 - 의도적으로 RouteKind가 아니다. 컴패니언 챗에는
 * ACTION_ROUTES/옵션/분기라는 개념이 전혀 없고, 오직 "캐릭터로서 답변함" 또는 "안전하게 리다이렉트됨"
 * (라우팅 프롬프트의 GENTLE_REDIRECT 규칙이 내리는 것과 동일한 안전 판단을 재사용)만 있을 뿐이다.
 */
public enum CompanionInteractionMode {
    ANSWER,
    GENTLE_REDIRECT;

    public static final List<String> ALL_NAMES = List.of("ANSWER", "GENTLE_REDIRECT");
}

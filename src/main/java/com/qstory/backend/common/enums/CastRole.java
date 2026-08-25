package com.qstory.backend.common.enums;

import java.util.List;

/**
 * 성우 캐스팅 항목의 역할 태그. Java enum이 아니라 일반 문자열인 이유: 이 값에 따라 분기하는
 * 로직이 전혀 없고, 클라이언트로 그대로 복사되어 전달될 뿐이기 때문(StoryContentRepository/CastEntry 참조).
 */
public final class CastRole {

    public static final String NARRATOR = "NARRATOR";
    public static final String CHARACTER = "CHARACTER";

    public static final List<String> VALUES = List.of(NARRATOR, CHARACTER);

    private CastRole() {}
}

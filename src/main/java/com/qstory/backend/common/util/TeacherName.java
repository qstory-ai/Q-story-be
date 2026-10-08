package com.qstory.backend.common.util;

/**
 * 알림 문구의 선생님 호칭. 표시 이름에 이미 "선생님"을 넣은 선생님이 많아서("햇살반 선생님") 그대로 붙이면
 * "햇살반 선생님 선생님이"가 된다 - 끝이 "선생님"이면 그대로, "선생"이면 "님"만, 나머지는 " 선생님"을 붙인다.
 */
public final class TeacherName {

    private TeacherName() {}

    public static String of(String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) return "선생님";
        if (name.endsWith("선생님")) return name;
        if (name.endsWith("선생")) return name + "님";
        return name + " 선생님";
    }
}

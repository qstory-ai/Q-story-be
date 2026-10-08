package com.qstory.backend.org.entity;

/**
 * 반 소속 이력(076)의 이유 값. JOINED는 시작 이유로만, GRADUATED는 끝난 이유로만 쓴다. MOVED·KEPT는 둘 다 -
 * 옮긴 반의 구간은 MOVED로 끝나고 새 반의 구간은 MOVED로 시작한다.
 */
public final class ClassMembershipReason {

    public static final String JOINED = "JOINED";
    public static final String MOVED = "MOVED";
    public static final String KEPT = "KEPT";
    public static final String GRADUATED = "GRADUATED";

    private ClassMembershipReason() {}
}

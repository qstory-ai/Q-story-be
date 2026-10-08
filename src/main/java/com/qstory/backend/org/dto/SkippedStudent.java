package com.qstory.backend.org.dto;

import java.util.UUID;

/**
 * 옮기지 못한 학생(076). reason:
 * NOT_IN_CLASS(이 반의 지금 학생이 아님), GRADUATED(이미 졸업), SAME_CLASS(옮길 반이 지금 반),
 * ALREADY_IN_TARGET(같은 아이가 옮길 반에 이미 있음), ALREADY_TUTOR_STUDENT(옮길 반 담임에게 같은 아이가 다른 학생으로 이미 있음).
 */
public record SkippedStudent(UUID studentId, String reason) {

    public static final String NOT_IN_CLASS = "NOT_IN_CLASS";
    public static final String GRADUATED = "GRADUATED";
    public static final String SAME_CLASS = "SAME_CLASS";
    public static final String ALREADY_IN_TARGET = "ALREADY_IN_TARGET";
    public static final String ALREADY_TUTOR_STUDENT = "ALREADY_TUTOR_STUDENT";
}

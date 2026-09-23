package com.qstory.backend.tutor.dto;

import java.util.UUID;

/**
 * @param lessonType INDIVIDUAL(기본) 또는 CLASS. CLASS면 classGroupId가 필요하다.
 * @param classGroupId 선생님이 볼 수 있는 반(GET /v1/tutor-classes)의 id.
 */
public record CreateTutorStudentRequest(
        String name, String ageBand, String classType, String prepNote, String lessonType, UUID classGroupId) {}

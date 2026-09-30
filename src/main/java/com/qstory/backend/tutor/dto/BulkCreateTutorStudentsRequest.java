package com.qstory.backend.tutor.dto;

import java.util.List;
import java.util.UUID;

/**
 * 한 반의 학생을 한 번에 등록하고 학생마다 LINK 초대까지 발급하는 요청. 수업 형태·반·메모는 전원 공통이고
 * 학생별로 다른 건 이름과 출생연도뿐이다. 출생연도를 비우면 defaultBirthYear를 쓴다.
 */
public record BulkCreateTutorStudentsRequest(
        List<Student> students, String lessonType, UUID classGroupId, String classType, String prepNote,
        Integer defaultBirthYear) {

    public record Student(String name, Integer birthYear) {}
}

package com.qstory.backend.tutor.dto;

/**
 * 학생 상세 화면에서 메모(prepNote)·수업 형태(classType) 등을 부분 수정한다. name/ageBand는
 * 학생 정체성을 정하는 값이라 여기선 편집을 지원하지 않는다 - 필요해지면 별도 필드로 열어 준다.
 * null 필드는 그대로 두고, 값이 있는 필드만 반영한다. 빈 문자열은 "값 지우기"로 해석된다.
 */
import java.util.UUID;

/**
 * lessonType/classGroupId: 둘 다 null이면 그대로. lessonType=INDIVIDUAL이면 반 연결을 지운다.
 * lessonType=CLASS면 classGroupId(또는 이미 붙어 있는 반)가 필요하다. classGroupId만 보내면 CLASS로 간주.
 */
public record UpdateTutorStudentRequest(String classType, String prepNote, String lessonType, UUID classGroupId) {}

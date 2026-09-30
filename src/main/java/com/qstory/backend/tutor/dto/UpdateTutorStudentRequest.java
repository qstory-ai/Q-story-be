package com.qstory.backend.tutor.dto;


/**
 * 학생 상세 화면에서 메모(prepNote)·수업 방식 메모(classType)를 부분 수정한다. 이름과 반은 바꾸지 않는다 - 반은
 * 학부모가 반 초대 링크로 연결할 때 정해진다. null 필드는 그대로 두고, 빈 문자열은 "값 지우기"로 해석된다.
 */
public record UpdateTutorStudentRequest(
        String classType, String prepNote,
        /** ~년생을 바꾸면 ageBand도 다시 계산된다. */
        Integer birthYear) {}

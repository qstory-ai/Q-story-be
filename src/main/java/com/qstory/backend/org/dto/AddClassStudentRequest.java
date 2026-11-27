package com.qstory.backend.org.dto;

/** 체험 모드에서 선생님이 미리 가입하지 않은 아이를 그 자리에서 반 명단에 올린다. 이름만 받는다. */
public record AddClassStudentRequest(String name) {}

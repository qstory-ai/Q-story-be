package com.qstory.backend.org.dto;

/** 이미 계정이 있는 학부모가 반 코드로 그 반의 학생 명단에 아이를 올린다. 로그인 정보는 JWT에서 얻는다. */
public record JoinExistingClassRequest(String classCode, String childName, Integer childBirthYear) {}

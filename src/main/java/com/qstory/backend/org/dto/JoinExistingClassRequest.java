package com.qstory.backend.org.dto;

/** 이미 계정이 있는 학부모가 반 코드로 그 반의 학생 명단에 아이를 올린다. 로그인 정보는 JWT에서 얻는다. */
public record JoinExistingClassRequest(
        String classCode, String childName, Integer childBirthYear,
        /** 이미 등록한 아이 프로필을 그대로 올릴 때(선택) - 있으면 이름·출생연도는 그 아이 것을 쓴다. */
        java.util.UUID childId) {}

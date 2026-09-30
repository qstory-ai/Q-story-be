package com.qstory.backend.org.dto;

/** 반 코드로 학부모 계정을 만들고 그 반의 학생 명단에 아이를 올린다. */
public record JoinClassRequest(
        String classCode, String loginId, String email, String password, String displayName,
        String childName, Integer childBirthYear) {}

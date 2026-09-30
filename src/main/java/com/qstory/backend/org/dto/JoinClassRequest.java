package com.qstory.backend.org.dto;

/** classCode(영구적, 재사용 가능)와 inviteToken(1회용) 중 정확히 하나만 존재해야 한다. */
public record JoinClassRequest(
        String classCode, String inviteToken, String loginId, String email, String password, String displayName,
        /** 선생님이 운영하는 반에 가입할 때만 필요 - 그 반의 학생 명단에 올라갈 아이의 이름·출생연도. */
        String childName, Integer childBirthYear) {}

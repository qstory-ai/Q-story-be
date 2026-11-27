package com.qstory.backend.org.dto;

/** 반 코드로 학부모 계정을 만들고 그 반의 학생 명단에 아이를 올린다. */
public record JoinClassRequest(
        String classCode, String loginId, String email, String password, String displayName,
        String childName, Integer childBirthYear,
        /** 이름이 명단과 달라도 선생님 명단의 이 학생과 잇는다(선택) - GET /v1/classes/by-code/{code}/roster의 id. */
        java.util.UUID rosterStudentId,
        /** 가입 동의(이용약관·개인정보 필수). */
        com.qstory.backend.identity.dto.ConsentPayload consents,
        /** 체험 모드 QR의 보호자 연결 코드(선택, 104) - 있으면 현장에서 올린 그 학생에 잇는다. */
        String studentClaimCode) {

    public JoinClassRequest(
            String classCode, String loginId, String email, String password, String displayName,
            String childName, Integer childBirthYear, java.util.UUID rosterStudentId,
            com.qstory.backend.identity.dto.ConsentPayload consents) {
        this(classCode, loginId, email, password, displayName, childName, childBirthYear, rosterStudentId, consents, null);
    }
}


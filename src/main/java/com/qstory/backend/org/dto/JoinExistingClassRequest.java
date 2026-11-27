package com.qstory.backend.org.dto;

/** 이미 계정이 있는 학부모가 반 코드로 그 반의 학생 명단에 아이를 올린다. 로그인 정보는 JWT에서 얻는다. */
public record JoinExistingClassRequest(
        String classCode, String childName, Integer childBirthYear,
        /** 이미 등록한 아이 프로필을 그대로 올릴 때(선택) - 있으면 이름·출생연도는 그 아이 것을 쓴다. */
        java.util.UUID childId,
        /** 이름이 명단과 달라도 선생님 명단의 이 학생과 잇는다(선택) - GET /v1/classes/by-code/{code}/roster의 id. */
        java.util.UUID rosterStudentId,
        /** 체험 모드 QR의 보호자 연결 코드(선택, 104) - 있으면 현장에서 올린 그 학생에 잇는다. */
        String studentClaimCode) {

    public JoinExistingClassRequest(
            String classCode, String childName, Integer childBirthYear, java.util.UUID childId,
            java.util.UUID rosterStudentId) {
        this(classCode, childName, childBirthYear, childId, rosterStudentId, null);
    }
}


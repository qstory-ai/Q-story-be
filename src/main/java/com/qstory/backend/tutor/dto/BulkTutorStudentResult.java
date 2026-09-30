package com.qstory.backend.tutor.dto;

/** 일괄 등록 결과 한 줄 - 학생과 그 학생의 부모 초대(코드·링크 토큰). */
public record BulkTutorStudentResult(TutorStudentResponse student, TutorInviteResponse invite) {}

package com.qstory.backend.tutor;

/** 학생 등록 직후엔 항상 PENDING_PARENT다 - 부모가 초대를 수락해야 CONFIRMED로 바뀐다. */
public enum TutorStudentStatus {
    PENDING_PARENT,
    CONFIRMED
}

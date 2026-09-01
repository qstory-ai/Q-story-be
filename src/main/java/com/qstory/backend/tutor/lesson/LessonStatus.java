package com.qstory.backend.tutor.lesson;

/**
 * IA "[3] 수업 목록"의 세 서브탭(예정/진행 중/완료)과 일대일. 상태 전환은 SCHEDULED → IN_PROGRESS
 * → COMPLETED 한 방향이며 역행은 지원하지 않는다(수업 결과가 지워지지 않도록).
 */
public enum LessonStatus {
    SCHEDULED, IN_PROGRESS, COMPLETED
}

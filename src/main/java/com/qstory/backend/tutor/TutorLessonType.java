package com.qstory.backend.tutor;

/**
 * 선생님이 학생을 등록할 때 고르는 수업 형태. INDIVIDUAL은 1:1 개인 레슨, CLASS는 반(class_group)에
 * 속한 학생 - CLASS면 tutor_student.class_group_id가 반드시 채워진다(TutorStudentService 참고).
 * 반 수업(Lesson.classGroup)을 만들면 그 반의 학생이 자동으로 참여 학생이 된다.
 */
public enum TutorLessonType {
    INDIVIDUAL,
    CLASS;

    public static TutorLessonType parseOrDefault(String raw) {
        if (raw == null || raw.isBlank()) return INDIVIDUAL;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

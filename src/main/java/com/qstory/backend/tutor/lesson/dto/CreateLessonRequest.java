package com.qstory.backend.tutor.lesson.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 수업 생성 요청. IA "새 수업 만들기" 스텝의 세 항목(이름/목표/일정)에 참여 학생과 이야기 배열이
 * 붙는다. goal/scheduledAt은 IA에서 명시적으로 null 허용, students/storyIds는 빈 리스트로도
 * 받는다(추후 상세 화면에서 추가 가능).
 */
public record CreateLessonRequest(
        String name,
        String goal,
        Instant scheduledAt,
        List<UUID> studentIds,
        List<String> storyIds) {}

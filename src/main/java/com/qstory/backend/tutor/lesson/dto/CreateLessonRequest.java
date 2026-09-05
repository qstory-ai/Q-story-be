package com.qstory.backend.tutor.lesson.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 수업 생성 요청. IA "새 수업 만들기" 스텝의 세 항목(이름/목표/일정)에 참여 학생과 이야기 배열이
 * 붙는다. goal/scheduledAt은 IA에서 명시적으로 null 허용, students/storyIds는 빈 리스트로도
 * 받는다(추후 상세 화면에서 추가 가능).
 *
 * @param seriesId 정기 수업 제출 시 프런트가 N번의 create 호출 전체에 같은 값을 실어 보내는
 *     클라이언트 생성 UUID - 그 형제 lesson들을 나중에 "향후 모든 수업 수정"으로 함께 찾기
 *     위함이다(Lesson.seriesId 참고). 단발성 수업은 null.
 */
public record CreateLessonRequest(
        String name,
        String goal,
        Instant scheduledAt,
        List<UUID> studentIds,
        List<String> storyIds,
        UUID seriesId) {}

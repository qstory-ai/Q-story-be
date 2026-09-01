package com.qstory.backend.tutor.lesson.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 부분 업데이트. 각 필드가 null이면 그대로 두고, 값이 있으면 그것으로 대체한다.
 * studentIds/storyIds는 배열 전체 교체 - 개별 추가/삭제 API를 따로 두지 않는다(간단히).
 */
public record UpdateLessonRequest(
        String name,
        String goal,
        Instant scheduledAt,
        List<UUID> studentIds,
        List<String> storyIds) {}

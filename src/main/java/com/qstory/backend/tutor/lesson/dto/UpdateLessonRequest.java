package com.qstory.backend.tutor.lesson.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 부분 업데이트. 각 필드가 null이면 그대로 두고, 값이 있으면 그것으로 대체한다.
 * studentIds/storyIds는 배열 전체 교체 - 개별 추가/삭제 API를 따로 두지 않는다(간단히).
 *
 * @param applyToFutureInSeries true이고 대상 lesson에 seriesId가 있으면, 같은 시리즈에서
 *     아직 SCHEDULED 상태이고 이 lesson과 같거나 이후 시각인 형제 lesson들에도 이 요청을
 *     함께 적용한다(LessonService.update 참고) - name/goal/studentIds/storyIds는 그대로
 *     복사하고, scheduledAt은 절대값이 아니라 "이 lesson의 기존 시각 대비 변화량"만큼 각자의
 *     시각에 더한다(요일/주기 자체를 바꾸는 게 아니라 같은 간격으로 시간만 밀거나 당기는 것).
 *     null/false면 이 lesson 하나만 수정하는 기존 동작 그대로.
 */
public record UpdateLessonRequest(
        String name,
        String goal,
        Instant scheduledAt,
        List<UUID> studentIds,
        List<String> storyIds,
        Boolean applyToFutureInSeries) {}

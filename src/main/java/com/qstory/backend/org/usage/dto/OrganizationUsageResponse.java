package com.qstory.backend.org.usage.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * IA "이용 현황 관리"의 요약 응답. IA 원문은 세부 지표를 나열하지 않지만, 대시보드 성격의
 * 카드 UI에 필요한 최소 값 몇 개(선생님 수, 반 수, 부모/학급 계정 수, 완주 수)를 담는다.
 * recentCompletions는 사용 흐름을 감지하는 "최근 활동" 카드용 - 최대 10개.
 */
public record OrganizationUsageResponse(
        int tutorCount,
        int classCount,
        int parentCount,
        int classAccountCount,
        long completionCount,
        List<RecentActivity> recentActivity) {

    public record RecentActivity(UUID completionId, String storyId, String actorDisplayName, Instant completedAt) {}
}

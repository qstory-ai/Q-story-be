package com.qstory.backend.storyreport.dto;

import com.qstory.backend.storyreport.entity.StoryCompletion;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * sessionKind·className·organizationName·tutorDisplayName·participantCount는 리포트 머리말용이다 - 반 수업
 * 기록(CLASS)은 "우리 반이 함께 나눈 이야기"로, 우리 아이 발화처럼 보이지 않게 그린다.
 *
 * <p>Q-39에서 더한 것:
 * - contentVersion·endStatus·readFromSceneId·readThroughSceneId: 회차 정보(작품 버전, 완주/중도 종료, 읽은 범위).
 * - turns: 그 회차에 실제로 오간 대화 전부(seq 순). 반 수업을 부모가 볼 때는 아이 말 text를 비운다.
 *   turnsAvailable=false면 보관 기간이 지나 지워진 것 - 프런트는 outcomes 요약만 보여 준다.
 * - teacherNote: internal은 선생님·관리자만, forParents는 모두.
 * - analysis: 관심·생각 관찰과 대화 카드(근거 대화 seq 포함). 상세 조회에서만 채운다.
 * - linkedChildren: 보는 부모의 이 기록과 이어진 자기 아이 - "아이랑 다시 읽기"가 고른다.
 */
public record StoryCompletionDetail(
        UUID id,
        String storyId,
        Instant completedAt,
        Integer durationSeconds,
        UUID childId,
        List<Map<String, Object>> outcomes,
        Map<String, Object> companionChatSummary,
        UUID tutorStudentId,
        UUID lessonId,
        String sessionKind,
        String className,
        String organizationName,
        String tutorDisplayName,
        int participantCount,
        String contentVersion,
        String endStatus,
        String readFromSceneId,
        String readThroughSceneId,
        List<Map<String, Object>> turns,
        boolean turnsAvailable,
        TeacherNote teacherNote,
        Map<String, Object> analysis,
        List<LinkedChild> linkedChildren,
        /**
         * 회차 id(= 대화 기록 id). 기록을 남긴 본인에게만 준다 - 부모·원장에게는 null(보안 점검 H1: 회차 id 전체를 알면 그
         * 회차에 기록을 덧붙이려 들 수 있다). 화면의 회차 코드는 sessionCode를 쓴다.
         */
        UUID sessionId,
        /** 리포트 맨 아래 UT 회차 코드(회차 id 앞 6자, 대문자) - 보는 사람 모두에게 준다(Q-40). 회차가 없으면 null. */
        String sessionCode) {

    public record TeacherNote(String internal, String forParents) {}

    public record LinkedChild(UUID id, String name) {}

    /** 목록·최근 기록용 - 대화·분석·메모 없이 기본 정보만. */
    public static StoryCompletionDetail of(StoryCompletion completion) {
        return of(completion, null, false, null, null, List.of(), true);
    }

    /** includeSessionId - 회차 id 전체를 실을지(기록을 남긴 본인만). 회차 코드는 늘 싣는다. */
    public static StoryCompletionDetail of(
            StoryCompletion completion, List<Map<String, Object>> turns, boolean turnsAvailable,
            TeacherNote teacherNote, Map<String, Object> analysis, List<LinkedChild> linkedChildren,
            boolean includeSessionId) {
        String kind = completion.sessionKind();
        return new StoryCompletionDetail(
                completion.getId(),
                completion.getStoryId(),
                completion.getCompletedAt(),
                completion.getDurationSeconds(),
                completion.getChild() == null ? null : completion.getChild().getId(),
                completion.getOutcomes(),
                completion.getCompanionChatSummary(),
                completion.getTutorStudent() == null ? null : completion.getTutorStudent().getId(),
                completion.getLesson() == null ? null : completion.getLesson().getId(),
                kind,
                completion.getClassGroup() == null ? null : completion.getClassGroup().getName(),
                completion.getOrganization() == null ? null : completion.getOrganization().getName(),
                "HOME".equals(kind) ? null : completion.getUser().getDisplayName(),
                completion.getParticipants().size(),
                completion.getContentVersion(),
                completion.getEndStatus(),
                completion.getReadFromSceneId(),
                completion.getReadThroughSceneId(),
                turns,
                turnsAvailable,
                teacherNote,
                analysis,
                linkedChildren,
                includeSessionId ? completion.getSessionId() : null,
                sessionCode(completion.getSessionId()));
    }

    /** fe entities/play-session sessionShortCode와 같은 규칙 - 하이픈을 뺀 앞 6자, 대문자. */
    static String sessionCode(UUID sessionId) {
        return sessionId == null ? null : sessionId.toString().replace("-", "").substring(0, 6).toUpperCase();
    }
}

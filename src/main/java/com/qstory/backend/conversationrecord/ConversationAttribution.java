package com.qstory.backend.conversationrecord;

import com.qstory.backend.common.enums.ConversationInputMode;
import com.qstory.backend.identity.security.CurrentUser;
import java.util.UUID;

/**
 * 한 발화가 "누구의, 어느 세션의" 것인지. 요청마다 프론트가 선택적으로 보내는 식별자
 * (sessionId/childId/tutorStudentId/lessonId/inputMode)와 인증 정보를 합친 것이다 - 전부 없어도
 * 파이프라인은 그대로 돌고 기록만 익명으로 남는다(익명 /demo 세션).
 *
 * <p>식별자는 서버가 소유권을 검증하지 않고 그대로 적는다: 이 기록은 열람 API가 없는 원장이라
 * 잘못된 id가 들어가도 다른 사용자에게 노출되는 경로가 없고, 검증을 넣으면 매 발화마다 DB 조회가
 * 추가된다. 나중에 읽는 기능이 생길 때 그 기능이 조인 시점에 소유권을 확인하면 된다.
 */
public record ConversationAttribution(
        UUID sessionId,
        UUID childId,
        UUID tutorStudentId,
        UUID lessonId,
        ConversationInputMode inputMode,
        UUID userId,
        String userRole) {

    public static ConversationAttribution anonymous(ConversationInputMode inputMode) {
        return new ConversationAttribution(null, null, null, null, inputMode, null, null);
    }

    public static ConversationAttribution of(
            UUID sessionId, UUID childId, UUID tutorStudentId, UUID lessonId,
            ConversationInputMode inputMode, CurrentUser callerOrNull) {
        return new ConversationAttribution(
                sessionId, childId, tutorStudentId, lessonId, inputMode,
                callerOrNull == null ? null : callerOrNull.userId(),
                callerOrNull == null || callerOrNull.role() == null ? null : callerOrNull.role().name());
    }

    public ConversationAttribution withInputMode(ConversationInputMode mode) {
        return new ConversationAttribution(sessionId, childId, tutorStudentId, lessonId, mode, userId, userRole);
    }
}

package com.qstory.backend.conversationrecord.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.common.enums.ConversationInputMode;
import com.qstory.backend.conversationrecord.ConversationAttribution;
import com.qstory.backend.identity.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 요청에서 선택적 세션 식별자(sessionId/childId/tutorStudentId/lessonId/inputMode)를 꺼낸다.
 * JSON 본문이 있는 라우트는 본문 필드로, 원시 오디오 바이트가 본문인 /v1/transcriptions·/v1/questions는
 * x-qstory-* 헤더로 받는다 - 리소스 컨텍스트(storyId 등)를 받는 방식과 같은 관례다.
 *
 * <p>전부 선택 사항이며 잘못된 UUID는 요청을 거절하지 않고 null로 둔다: 기록은 부가 기능이라
 * 이것 때문에 아이의 질문이 실패해서는 안 된다.
 */
@Component
public class ConversationAttributionParser {

    public ConversationAttribution fromBody(JsonNode body, ConversationInputMode defaultMode, CurrentUser callerOrNull) {
        return ConversationAttribution.of(
                uuidOrNull(text(body, "sessionId")),
                uuidOrNull(text(body, "childId")),
                uuidOrNull(text(body, "tutorStudentId")),
                uuidOrNull(text(body, "lessonId")),
                ConversationInputMode.parseOrDefault(text(body, "inputMode"), defaultMode),
                callerOrNull);
    }

    public ConversationAttribution fromHeaders(
            HttpServletRequest request, ConversationInputMode defaultMode, CurrentUser callerOrNull) {
        return ConversationAttribution.of(
                uuidOrNull(request.getHeader("x-qstory-session-id")),
                uuidOrNull(request.getHeader("x-qstory-child-id")),
                uuidOrNull(request.getHeader("x-qstory-tutor-student-id")),
                uuidOrNull(request.getHeader("x-qstory-lesson-id")),
                ConversationInputMode.parseOrDefault(request.getHeader("x-qstory-input-mode"), defaultMode),
                callerOrNull);
    }

    private static String text(JsonNode body, String field) {
        if (body == null) return null;
        JsonNode node = body.path(field);
        return node.isTextual() ? node.asText().trim() : null;
    }

    private static UUID uuidOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}

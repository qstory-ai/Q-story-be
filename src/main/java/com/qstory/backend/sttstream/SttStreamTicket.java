package com.qstory.backend.sttstream;

import com.qstory.backend.conversationrecord.ConversationAttribution;
import java.time.Instant;
import java.util.List;

/**
 * 실시간 받아 적기 표 한 장 - 보통의(프록시를 거치는) POST로 받아 WebSocket 주소(?ticket=)에 한 번만 쓴다.
 * 브라우저 WebSocket은 Authorization 헤더를 못 실으니, 누가·어느 이야기에서 말하는지는 표를 줄 때 정해 둔다.
 */
public record SttStreamTicket(
        String id,
        String storyId,
        String sceneId,
        List<String> sttKeywords,
        ConversationAttribution attribution,
        Instant expiresAt) {

    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}

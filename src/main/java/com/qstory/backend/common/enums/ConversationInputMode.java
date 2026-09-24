package com.qstory.backend.common.enums;

/** 아이의 발화가 어떻게 들어왔는지. VOICE는 STT 결과, TEXT는 직접 입력. */
public enum ConversationInputMode {
    VOICE,
    TEXT;

    /** 프론트가 보낸 문자열(대소문자 무관). 없거나 모르는 값이면 fallback. */
    public static ConversationInputMode parseOrDefault(String raw, ConversationInputMode fallback) {
        if (raw == null) return fallback;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}

package com.qstory.backend.provider.gemini.util;

/**
 * GeminiTtsClient가 Gemini에 요청을 하나 보낼 때마다(onCall), 429를 받으면(onRateLimited) 부른다. 음성 응답 경로에서
 * 불리므로 바로 돌아와야 하고 예외를 던지면 안 된다(무거운 일은 다른 스레드로).
 */
public interface GeminiTtsUsageListener {

    GeminiTtsUsageListener NONE = new GeminiTtsUsageListener() {
        @Override
        public void onCall() {}

        @Override
        public void onRateLimited() {}
    };

    void onCall();

    void onRateLimited();
}

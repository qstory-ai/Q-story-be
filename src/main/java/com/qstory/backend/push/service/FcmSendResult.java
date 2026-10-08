package com.qstory.backend.push.service;

/**
 * 토큰 하나로 보낸 결과. TOKEN_INVALID는 FCM이 그 토큰을 다시는 받지 않는다고 답한 경우(UNREGISTERED 등)라
 * 호출자가 토큰을 비활성화한다. FAILED는 일시 장애·설정 문제일 수 있어 토큰은 그대로 둔다.
 */
public record FcmSendResult(Outcome outcome, int status, String reason) {

    public enum Outcome {
        SENT,
        TOKEN_INVALID,
        FAILED
    }

    static FcmSendResult sent(int status) {
        return new FcmSendResult(Outcome.SENT, status, null);
    }
}

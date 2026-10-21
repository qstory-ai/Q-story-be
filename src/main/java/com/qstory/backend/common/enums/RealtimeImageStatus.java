package com.qstory.backend.common.enums;

/** 실시간 삽화 한 장의 생명주기 - 자리 잡음(QUEUED) → 만드는 중 → 올림(READY) / 실패. 실패한 행은 상한에 세지 않는다. */
public enum RealtimeImageStatus {
    QUEUED,
    GENERATING,
    READY,
    FAILED
}

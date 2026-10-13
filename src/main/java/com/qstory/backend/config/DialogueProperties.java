package com.qstory.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 그레텔 대화 스위치. tryAndFailEnabled - 질문 초대에서 몰래 가져가기·거짓말·혼자 도망치기처럼 결과로 배울 수 있는
 * 행동을 거절하지 않고 짧은 "실패해 보기" 장면(replyKind TRY_FAIL)으로 보여 준다. 문제가 생기면
 * QSTORY_TRY_AND_FAIL_ENABLED=false 하나로 예전 대화(GRETEL_DIALOGUE_V2)로 돌아간다. 값이 없으면 켜진 것으로 본다.
 */
@ConfigurationProperties(prefix = "qstory.dialogue")
public record DialogueProperties(Boolean tryAndFailEnabled) {

    public DialogueProperties {
        tryAndFailEnabled = tryAndFailEnabled == null || tryAndFailEnabled;
    }

    public boolean tryAndFail() {
        return tryAndFailEnabled;
    }
}

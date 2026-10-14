package com.qstory.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 그레텔 대화 스위치와 상한.
 *
 * <p>tryAndFailEnabled - 질문 초대에서 아이가 준비되지 않은 행동(창문으로 나가자, 몰래 가져가자 등)을 내면 거절하지 않고
 * 짧은 "시도 장면"(replyKind TRY_FAIL)을 만들어 보여 준 뒤, 그 장면 안에서 몇 번 더 이야기하고 원래 질문 지점으로 돌아온다.
 * 다치거나 남을 해치는 행동은 그대로 막는다. 문제가 생기면 QSTORY_TRY_AND_FAIL_ENABLED=false 하나로 예전 대화
 * (GRETEL_DIALOGUE_V2)로 돌아간다. 값이 없으면 켜진 것으로 본다.
 *
 * @param maxAttemptsPerAnchor 질문 지점 하나에서 만들 수 있는 시도 장면 수 - 장면마다 내레이터·인물 음성(TTS)이 몇 번씩
 *                             더 들어서 하루 TTS 한도를 지키려는 상한이다(기본 1).
 * @param maxAttemptTurns      시도 장면 안에서 아이가 말할 수 있는 횟수 - 이 횟수의 답에서 그레텔이 원래 질문으로 돌아온다(기본 4).
 */
@ConfigurationProperties(prefix = "qstory.dialogue")
public record DialogueProperties(Boolean tryAndFailEnabled, Integer maxAttemptsPerAnchor, Integer maxAttemptTurns) {

    /** 생성자가 둘이라 바인딩할 생성자를 명시한다 - 없으면 Spring이 기본 생성자를 찾다 부팅에 실패한다. */
    @ConstructorBinding
    public DialogueProperties {
        tryAndFailEnabled = tryAndFailEnabled == null || tryAndFailEnabled;
        maxAttemptsPerAnchor = maxAttemptsPerAnchor == null || maxAttemptsPerAnchor < 0 ? 1 : maxAttemptsPerAnchor;
        maxAttemptTurns = maxAttemptTurns == null || maxAttemptTurns < 1 ? 4 : Math.min(maxAttemptTurns, 8);
    }

    public DialogueProperties(Boolean tryAndFailEnabled) {
        this(tryAndFailEnabled, null, null);
    }

    public boolean tryAndFail() {
        return tryAndFailEnabled;
    }
}

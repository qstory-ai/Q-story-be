package com.qstory.backend.common.enums;

/** conversation_record 한 행이 무엇을 담는지. 발화 하나가 STT 행과 답변 행 두 개로 남을 수 있다. */
public enum ConversationRecordKind {
    /** 질문 지점에서 녹음이 STT를 거쳐 문장이 된 시점. 답은 아직 없다. */
    QUESTION_TRANSCRIPT,
    /** 질문 지점의 발화(음성 또는 글)가 라우팅되어 캐릭터의 답이 정해진 시점. */
    QUESTION_ROUTE,
    /** 상시 대화에서 녹음이 STT를 거쳐 문장이 된 시점. */
    COMPANION_TRANSCRIPT,
    /** 상시 대화 한 턴 - 아이의 말과 캐릭터의 답. */
    COMPANION_TURN,
}

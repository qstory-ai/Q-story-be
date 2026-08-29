package com.qstory.backend.common.util;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import java.util.UUID;

/**
 * BetaEventValidator/CompanionChatController/VoiceResearchController가 각자 따로 손으로 다시
 * 작성했던 "문자열을 UUID로 파싱하고 실패하면 ApiException을 던진다" 로직과, 여러 검증기가
 * 독립적으로 하드코딩하고 있던 짧은 텍스트 길이 제한(240자)을 하나로 모았다.
 */
public final class ValidationSupport {

    /** BetaEventValidator/QuestionContractValidator/VoiceResearchValidator가 공유하는 짧은 텍스트 길이 제한. */
    public static final int MAX_SHORT_TEXT_LENGTH = 240;

    private ValidationSupport() {}

    public static UUID parseUuid(String raw, ErrorCode code, String safeDetail) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw ApiException.contractError(code, safeDetail);
        }
    }
}

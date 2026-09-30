package com.qstory.backend.common.util;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import java.util.UUID;

/** 여러 검증기가 공유하는 요청 검증 헬퍼(UUID 파싱, 짧은 텍스트 길이 제한). */
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

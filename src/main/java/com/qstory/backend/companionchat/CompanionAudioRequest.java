package com.qstory.backend.companionchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import java.util.Set;
import java.util.regex.Pattern;

/** 컴패니언 챗 base64 음성 인식 요청의 컨텍스트 - JSON body의 storyId/sceneId와 오디오 타입. */
public record CompanionAudioRequest(String storyId, String sceneId, String sourceMimeType) {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9-]{0,63}$");
    private static final Set<String> SUPPORTED_AUDIO_TYPES = Set.of(
            "audio/mp4", "audio/m4a", "audio/mpeg", "audio/wav", "audio/x-wav", "audio/flac", "audio/webm");

    public static CompanionAudioRequest fromBody(JsonNode body, String mimeType) {
        String storyId = checkedIdentifier(requiredBodyField(body, "storyId"), "storyId");
        String sceneId = checkedIdentifier(requiredBodyField(body, "sceneId"), "sceneId");
        return new CompanionAudioRequest(storyId, sceneId, checkedAudioMimeType(mimeType));
    }

    private static String checkedAudioMimeType(String rawContentType) {
        String base = rawContentType.split(";", 2)[0].trim().toLowerCase();
        if (!SUPPORTED_AUDIO_TYPES.contains(base)) {
            throw ApiException.contractError(ErrorCode.UNSUPPORTED_AUDIO_TYPE, "The uploaded recording type is not supported");
        }
        return base;
    }

    private static String requiredBodyField(JsonNode body, String name) {
        String value = body == null ? "" : body.path(name).asText("").trim();
        if (value.isEmpty()) {
            throw ApiException.contractError(ErrorCode.MISSING_REQUEST_CONTEXT, name + " is required");
        }
        return value;
    }

    private static String checkedIdentifier(String value, String field) {
        if (!IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw ApiException.contractError(ErrorCode.INVALID_REQUEST_CONTEXT, field + " is invalid");
        }
        return value;
    }
}

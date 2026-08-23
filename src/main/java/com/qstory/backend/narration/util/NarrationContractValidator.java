package com.qstory.backend.narration.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import org.springframework.stereotype.Component;

/** Java port of narration-contract.mjs's parseNarrationRequest (validation only - context resolution is StoryRegistryService's job). */
@Component
public class NarrationContractValidator {

    private static final java.util.regex.Pattern CONTROL_CHARS = java.util.regex.Pattern.compile("[\\u0000-\\u001F\\u007F]");

    public record NarrationRequest(String storyId, String anchorId, String speakerId, String text) {}

    public NarrationRequest parse(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw ApiException.contractError(ErrorCode.INVALID_NARRATION_REQUEST, "Narration request must be an object");
        }
        String storyId = value.path("storyId").asText("").trim();
        String anchorId = value.path("anchorId").asText("").trim();
        String speakerId = value.path("speakerId").asText("").trim();
        String text = value.path("text").asText("").trim();

        if (text.isEmpty() || text.length() > 120
                || text.contains("{child_name}") || text.contains("{child_call}")
                || CONTROL_CHARS.matcher(text).find()) {
            throw ApiException.contractError(ErrorCode.INVALID_NARRATION_TEXT, "Narration text is invalid");
        }
        return new NarrationRequest(storyId, anchorId, speakerId, text);
    }
}

package com.qstory.backend.question;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Java port of question-contract.mjs. */
@Component
public class QuestionContractValidator {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9-]{0,63}$");
    private static final Pattern FAMILY_ID_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");
    private static final Set<String> SUPPORTED_AUDIO_TYPES = Set.of(
            "audio/mp4", "audio/m4a", "audio/mpeg", "audio/wav", "audio/x-wav", "audio/flac", "audio/webm");

    public record HeaderContext(String storyId, String sceneId, String anchorId, int questionRound, String sourceMimeType) {}

    public record TextQuestion(
            String storyId, String sceneId, String anchorId, int questionRound, boolean guaranteeAgencyChoice,
            List<String> priorActionFamilyIds, String transcript) {}

    public HeaderContext parseQuestionContext(HttpServletRequest request) {
        String contentType = request.getHeader("content-type");
        return resolveHeaderContext(request, contentType == null ? "" : contentType);
    }

    /** Used for the base64 upload route: the mimeType comes from the decoded JSON body, not the request's own content-type (application/json). */
    public HeaderContext parseQuestionContextForMimeType(HttpServletRequest request, String mimeType) {
        return resolveHeaderContext(request, mimeType);
    }

    private HeaderContext resolveHeaderContext(HttpServletRequest request, String rawContentType) {
        String storyId = checkedIdentifier(requiredHeader(request, "x-qstory-story-id"), "storyId");
        String sceneId = checkedIdentifier(requiredHeader(request, "x-qstory-scene-id"), "sceneId");
        String anchorId = checkedIdentifier(requiredHeader(request, "x-qstory-anchor-id"), "anchorId");
        int questionRound = parseQuestionRound(requiredHeader(request, "x-qstory-question-round"));

        String base = rawContentType.split(";", 2)[0].trim().toLowerCase();
        if (!SUPPORTED_AUDIO_TYPES.contains(base)) {
            throw ApiException.contractError(ErrorCode.UNSUPPORTED_AUDIO_TYPE, "The uploaded recording type is not supported");
        }
        return new HeaderContext(storyId, sceneId, anchorId, questionRound, base);
    }

    public TextQuestion parseTextQuestionRequest(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw ApiException.contractError(ErrorCode.INVALID_TEXT_QUESTION, "Text question is required");
        }
        List<String> priorActionFamilyIds = new ArrayList<>();
        if (value.has("priorActionFamilyIds") && value.get("priorActionFamilyIds").isArray()) {
            LinkedHashSet<String> deduped = new LinkedHashSet<>();
            value.get("priorActionFamilyIds").forEach(node -> deduped.add(node.asText("").trim()));
            priorActionFamilyIds.addAll(deduped);
        }
        if (priorActionFamilyIds.size() > 3
                || priorActionFamilyIds.stream().anyMatch(familyId -> !FAMILY_ID_PATTERN.matcher(familyId).matches())) {
            throw ApiException.contractError(
                    ErrorCode.INVALID_REQUEST_CONTEXT, "priorActionFamilyIds must contain up to three valid family ids");
        }

        String storyId = checkedIdentifier(value.path("storyId").asText("").trim(), "storyId");
        String sceneId = checkedIdentifier(value.path("sceneId").asText("").trim(), "sceneId");
        String anchorId = checkedIdentifier(value.path("anchorId").asText("").trim(), "anchorId");
        int questionRound = parseQuestionRound(value.path("questionRound").asText(""));
        boolean guaranteeAgencyChoice = value.path("guaranteeAgencyChoice").asBoolean(false);

        String transcript = value.path("transcript").asText("").trim();
        if (transcript.isEmpty() || transcript.length() > 240) {
            throw ApiException.contractError(
                    ErrorCode.INVALID_TEXT_QUESTION, "Text question must be between 1 and 240 characters");
        }
        return new TextQuestion(
                storyId, sceneId, anchorId, questionRound, guaranteeAgencyChoice, priorActionFamilyIds, transcript);
    }

    private String requiredHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.trim().isEmpty()) {
            throw ApiException.contractError(ErrorCode.MISSING_REQUEST_CONTEXT, name + " is required");
        }
        return value.trim();
    }

    private String checkedIdentifier(String value, String field) {
        if (!IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw ApiException.contractError(ErrorCode.INVALID_REQUEST_CONTEXT, field + " is invalid");
        }
        return value;
    }

    private int parseQuestionRound(String raw) {
        try {
            int questionRound = Integer.parseInt(raw.trim());
            if (questionRound < 1 || questionRound > 10) {
                throw new NumberFormatException();
            }
            return questionRound;
        } catch (NumberFormatException error) {
            throw ApiException.contractError(ErrorCode.INVALID_REQUEST_CONTEXT, "questionRound must be between 1 and 10");
        }
    }
}

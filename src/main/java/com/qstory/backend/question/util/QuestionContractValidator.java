package com.qstory.backend.question.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.ValidationSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** question-contract.mjs를 Java로 이식한 것. */
@Component
public class QuestionContractValidator {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9-]{0,63}$");
    private static final Pattern FAMILY_ID_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");
    private static final Set<String> SUPPORTED_AUDIO_TYPES = Set.of(
            "audio/mp4", "audio/m4a", "audio/mpeg", "audio/wav", "audio/x-wav", "audio/flac", "audio/webm");

    public record HeaderContext(String storyId, String sceneId, String anchorId, int questionRound, String sourceMimeType) {}

    /** 컴패니언 챗 음성 인식용 - anchor/questionRound 없이 storyId/sceneId만 필요하다. */
    public record CompanionAudioContext(String storyId, String sceneId, String sourceMimeType) {}

    public record TextQuestion(
            String storyId, String sceneId, String anchorId, int questionRound, boolean guaranteeAgencyChoice,
            List<String> priorActionFamilyIds, String transcript) {}

    public HeaderContext parseQuestionContext(HttpServletRequest request) {
        String contentType = request.getHeader("content-type");
        return resolveHeaderContext(request, contentType == null ? "" : contentType);
    }

    /** base64 업로드 라우트에서 사용된다: mimeType은 요청 자체의 content-type(application/json)이 아니라 디코딩된 JSON 본문에서 가져온다. */
    public HeaderContext parseQuestionContextForMimeType(HttpServletRequest request, String mimeType) {
        return resolveHeaderContext(request, mimeType);
    }

    /**
     * /v1/transcriptions/base64 전용 - storyId/sceneId/anchorId/questionRound를 헤더가 아니라
     * (오디오와 같이 이미 파싱된) JSON body에서 읽는다. 리소스 컨텍스트가 URL이나 body에 있어야
     * 한다는 원칙에 맞추면서, 같은 정보를 body로 받는 형제 엔드포인트 POST /v1/questions/route와
     * 일관되게 맞춘다.
     */
    public HeaderContext parseQuestionContextFromBody(JsonNode body, String mimeType) {
        String storyId = checkedIdentifier(requiredBodyField(body, "storyId"), "storyId");
        String sceneId = checkedIdentifier(requiredBodyField(body, "sceneId"), "sceneId");
        String anchorId = checkedIdentifier(requiredBodyField(body, "anchorId"), "anchorId");
        int questionRound = parseQuestionRound(requiredBodyField(body, "questionRound"));
        return new HeaderContext(storyId, sceneId, anchorId, questionRound, checkedAudioMimeType(mimeType));
    }

    /** 컴패니언 챗의 base64 음성 인식 라우트에서 사용된다 - anchor/questionRound 헤더는 요구하지 않는다. */
    public CompanionAudioContext parseCompanionAudioContext(HttpServletRequest request, String mimeType) {
        String storyId = checkedIdentifier(requiredHeader(request, "x-qstory-story-id"), "storyId");
        String sceneId = checkedIdentifier(requiredHeader(request, "x-qstory-scene-id"), "sceneId");
        return new CompanionAudioContext(storyId, sceneId, checkedAudioMimeType(mimeType));
    }

    /** parseCompanionAudioContext의 body 버전 - storyId/sceneId를 헤더가 아니라 JSON body에서 읽는다. */
    public CompanionAudioContext parseCompanionAudioContextFromBody(JsonNode body, String mimeType) {
        String storyId = checkedIdentifier(requiredBodyField(body, "storyId"), "storyId");
        String sceneId = checkedIdentifier(requiredBodyField(body, "sceneId"), "sceneId");
        return new CompanionAudioContext(storyId, sceneId, checkedAudioMimeType(mimeType));
    }

    private HeaderContext resolveHeaderContext(HttpServletRequest request, String rawContentType) {
        String storyId = checkedIdentifier(requiredHeader(request, "x-qstory-story-id"), "storyId");
        String sceneId = checkedIdentifier(requiredHeader(request, "x-qstory-scene-id"), "sceneId");
        String anchorId = checkedIdentifier(requiredHeader(request, "x-qstory-anchor-id"), "anchorId");
        int questionRound = parseQuestionRound(requiredHeader(request, "x-qstory-question-round"));
        return new HeaderContext(storyId, sceneId, anchorId, questionRound, checkedAudioMimeType(rawContentType));
    }

    private String checkedAudioMimeType(String rawContentType) {
        String base = rawContentType.split(";", 2)[0].trim().toLowerCase();
        if (!SUPPORTED_AUDIO_TYPES.contains(base)) {
            throw ApiException.contractError(ErrorCode.UNSUPPORTED_AUDIO_TYPE, "The uploaded recording type is not supported");
        }
        return base;
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
        if (transcript.isEmpty() || transcript.length() > ValidationSupport.MAX_SHORT_TEXT_LENGTH) {
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

    private String requiredBodyField(JsonNode body, String name) {
        String value = body == null ? "" : body.path(name).asText("").trim();
        if (value.isEmpty()) {
            throw ApiException.contractError(ErrorCode.MISSING_REQUEST_CONTEXT, name + " is required");
        }
        return value;
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

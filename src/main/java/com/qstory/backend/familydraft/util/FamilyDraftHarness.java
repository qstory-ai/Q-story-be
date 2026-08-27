package com.qstory.backend.familydraft.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.provider.openrouter.util.OpenRouterClient;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * ShadowFamilyGenerationService(구, staff 트리거, 단일 family, 스테이징 테이블)와
 * LiveBranchExecutionWorker(신규, 비동기, family 최대 3개 동시, 실제 콘텐츠 테이블)가 공유하는
 * "LLM draft 생성 하네스"(draft 요청 -> 검증 -> 자동 검수 게이트 -> 재시도) 공통 로직을 모은 것.
 *
 * <p>두 서비스는 서로 다른 도메인 모델을 갖는다 - Shadow는 하드코딩된 AnchorContract(bare label,
 * 예: "GRETEL"), LiveBranch는 StoryAnchor/StoryCast(접두 speakerId, 예: "HG-SPK-GRETEL", 자세한 매핑은
 * StoryCast.speakerId 문서 참고)를 쓴다. 그래서 이 클래스는 "허용 인물 목록"을 하나의 정규화된 형식으로
 * 강제하지 않는다 - 각 호출자가 자기 draft가 실제로 담고 있는 형식 그대로 Collection&lt;String&gt;을
 * 넘기게 하고, 이 클래스는 그 값들을 그대로 비교만 한다. 두 서비스의 validationIssue()가 검사 순서까지
 * 서로 달라(예: Shadow는 proposedFamilyId/location 검사가 그 사이에 끼어 있고, LiveBranch는 이미지 인물
 * 검사가 beats 검사 뒤에 온다) 하나의 고정 순서로 합치면 여러 문제가 동시에 있는 초안에서 재시도
 * 피드백으로 돌아가는 "첫 실패 사유" 문구가 달라질 수 있으므로, 검사 하나하나를 별도 메서드로 쪼개
 * 각 서비스가 자신의 기존 순서대로 조합해 부르게 한다.
 */
@Component
public class FamilyDraftHarness {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(?:\\+?82[- ]?)?0?1[016789][- ]?\\d{3,4}[- ]?\\d{4}");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_PATTERN = Pattern.compile("(?:내|제)\\s*이름은\\s*\\p{L}{1,20}");

    private final ObjectMapper objectMapper;
    private final OpenRouterClient openRouterClient;

    public FamilyDraftHarness(ObjectMapper objectMapper, OpenRouterClient openRouterClient) {
        this.objectMapper = objectMapper;
        this.openRouterClient = openRouterClient;
    }

    /**
     * 아이 발화를 DB에 남기기 전에 이메일/전화/URL/이름을 지운다 - Shadow(160자)와 LiveBranch(400자)는
     * 저장 필드 길이만 다르고 나머지 규칙은 완전히 같아서 maxLength만 호출자가 정한다.
     */
    public String redact(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String redacted = EMAIL_PATTERN.matcher(value).replaceAll("[email]");
        redacted = PHONE_PATTERN.matcher(redacted).replaceAll("[phone]");
        redacted = URL_PATTERN.matcher(redacted).replaceAll("[url]");
        redacted = NAME_PATTERN.matcher(redacted).replaceAll("이름을 말함");
        redacted = redacted.replaceAll("\\s+", " ").trim();
        return redacted.length() > maxLength ? redacted.substring(0, maxLength) : redacted;
    }

    /** 이미지 생성이 특히 느릴 수 있어 실시간 라우팅용 기본값(requestTimeoutMs)보다 여유 있게 잡는다. */
    public RequestDeadline freshDeadline(long requestTimeoutMs) {
        return RequestDeadline.startingNow(Math.max(requestTimeoutMs, 60_000));
    }

    /**
     * 자동 LLM 검수 게이트 호출 - pass 조건과 실패 시 issues 폴백 문구는 두 호출자가 완전히 동일하다.
     * {@code payload}에는 호출자가 자신의 식별 필드(예: Shadow의 "anchorId", LiveBranch의 "anchorSlot")를
     * 미리 채워 넣어야 한다 - "draft" 필드는 여기서 채운다.
     */
    public List<String> reviewGateIssues(
            ObjectNode payload, JsonNode draft, String systemPrompt, String schemaName, String failureSafeDetail,
            RequestDeadline deadline) {
        payload.set("draft", draft);
        JsonNode review = openRouterClient.generateStructuredCompletion(
                systemPrompt, payload.toString(), reviewSchema(), schemaName, 800,
                ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, failureSafeDetail, deadline);
        boolean pass = review.path("pass").asBoolean(false)
                && review.path("intentAlignment").asInt(-1) == 2
                && review.path("continuity").asInt(-1) == 2
                && review.path("safety").asInt(-1) == 2
                && review.path("fixedEventCopy").asInt(-1) == 2;
        if (pass) {
            return List.of();
        }
        List<String> issues = new ArrayList<>();
        if (review.path("issues").isArray()) {
            review.path("issues").forEach(node -> issues.add(node.asText()));
        }
        if (issues.isEmpty()) {
            issues.add("신규 family 초안 품질 검수를 통과하지 못했다.");
        }
        return issues;
    }

    public ObjectNode reviewSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("pass").put("type", "boolean");
        ObjectNode issues = properties.putObject("issues");
        issues.put("type", "array");
        issues.put("minItems", 0);
        issues.put("maxItems", 8);
        issues.putObject("items").put("type", "string");
        for (String field : List.of("intentAlignment", "continuity", "safety", "fixedEventCopy")) {
            ObjectNode score = properties.putObject(field);
            score.put("type", "integer");
            score.put("minimum", 0);
            score.put("maximum", 2);
        }
        schema.putArray("required").add("pass").add("issues")
                .add("intentAlignment").add("continuity").add("safety").add("fixedEventCopy");
        return schema;
    }

    public void stringProp(ObjectNode properties, String name, int minLength, int maxLength) {
        ObjectNode node = properties.putObject(name);
        node.put("type", "string");
        node.put("minLength", minLength);
        node.put("maxLength", maxLength);
    }

    // ---- validationIssue 빌딩 블록: 통과하면 null, 아니면 재시도 사유 문자열 ----

    public String rejoinAnchorIssue(JsonNode draft, Collection<String> allowedRejoinAnchorIds) {
        if (!allowedRejoinAnchorIds.contains(draft.path("rejoinAnchorId").asText(""))) {
            return "rejoinAnchorId는 입력으로 주어진 allowedRejoinAnchorIds 중 하나여야 한다.";
        }
        return null;
    }

    /**
     * imageBrief.characters가 최소 1명 이상이고 전부 허용 목록 안에 있는지 검사한다.
     * {@code allowedCharacters}는 호출자가 실제로 draft에 채워 넣은 것과 같은 형식이어야 한다
     * (Shadow는 bare label, LiveBranch는 접두 speakerId - 클래스 문서 참고).
     */
    public String imageCharactersIssue(JsonNode draft, Collection<String> allowedCharacters) {
        JsonNode imageBrief = draft.path("imageBrief");
        if (!imageBrief.path("characters").isArray() || imageBrief.path("characters").isEmpty()) {
            return "imageBrief.characters는 최소 1명 이상이어야 한다.";
        }
        for (JsonNode character : imageBrief.path("characters")) {
            if (!allowedCharacters.contains(character.asText(""))) {
                return "imageBrief.characters는 허용된 인물 목록만 써야 한다.";
            }
        }
        return null;
    }

    public String choiceCopyIssue(JsonNode draft) {
        JsonNode choiceCopy = draft.path("choiceCopy");
        if (!choiceCopy.isArray() || choiceCopy.size() != 3) {
            return "choiceCopy는 정확히 3개여야 한다.";
        }
        Set<String> labels = new HashSet<>();
        choiceCopy.forEach(node -> labels.add(node.path("label").asText("")));
        if (labels.size() != 3) {
            return "choiceCopy의 label 3개는 서로 달라야 한다.";
        }
        return null;
    }

    /**
     * beats 개수(1~2) + 각 beat의 action/narratorText 비어있음 여부 + dialogue.speaker가 허용 인물
     * 또는 NARRATOR인지를 검사한다. {@code allowedCharacters}는 {@link #imageCharactersIssue}와 같은
     * 형식이면 되고, NARRATOR는 이 메서드가 알아서 더한다.
     */
    public String beatsIssue(JsonNode draft, Collection<String> allowedCharacters) {
        JsonNode beats = draft.path("beats");
        if (!beats.isArray() || beats.isEmpty() || beats.size() > 2) {
            return "beats는 1~2개여야 한다.";
        }
        Set<String> allowedSpeakers = new HashSet<>(allowedCharacters);
        allowedSpeakers.add("NARRATOR");
        for (JsonNode beat : beats) {
            if (beat.path("action").asText("").isBlank() || beat.path("narratorText").asText("").isBlank()) {
                return "beats의 action/narratorText는 비어 있으면 안 된다.";
            }
            for (JsonNode line : beat.path("dialogue")) {
                if (!allowedSpeakers.contains(line.path("speaker").asText(""))) {
                    return "dialogue의 speaker는 허용된 인물이나 NARRATOR만 써야 한다.";
                }
            }
        }
        return null;
    }

    public String reportSummaryIssue(JsonNode draft) {
        String reportSummary = draft.path("reportSummary").asText("");
        if (reportSummary.contains("초안") || reportSummary.contains("생성함")) {
            return "reportSummary는 생성 과정이 아니라 아이가 고른 행동과 결과를 요약해야 한다.";
        }
        return null;
    }

    // ---- 이미지 프롬프트: 두 서비스가 동일하게 쓰는 고정 템플릿 줄만 여기 있다 ----

    /**
     * 두 서비스의 buildImagePrompt()가 공유하는 고정 템플릿 줄들. {@code locationOrSceneLine}은
     * 호출자가 이미 완성한 한 줄을 그대로 넣는다(Shadow는 "Location: ...", LiveBranch는
     * "Scene: ..." - 서로 다른 소스에서 온 값이라 이 클래스가 대신 만들지 않는다). 반환된 리스트는
     * 가변(mutable)이라 LiveBranch가 character-identity-rules 줄을 추가로 append할 수 있다.
     */
    public List<String> baseImagePromptLines(List<String> characters, String locationOrSceneLine, JsonNode imageBrief) {
        return new ArrayList<>(List.of(
                "Create one unpublished Korean children's storybook illustration for ages 6-9.",
                "Landscape 16:10 composition, hand-painted texture, detailed linework, warm brown and violet palette.",
                "Keep the exact character faces, clothing, body proportions, location geometry and prop scale from the reference image.",
                "Characters: " + String.join(", ", characters) + ".",
                locationOrSceneLine,
                "Required action: " + imageBrief.path("action").asText() + ".",
                "Composition: " + imageBrief.path("composition").asText() + ".",
                "Continuity facts: " + String.join("; ", toStringList(imageBrief.path("continuityFacts"))) + ".",
                "Keep the lower caption-safe area free of important faces, hands and key actions.",
                "Do not include: " + imageBrief.path("negativePrompt").asText()
                        + "; text, letters, UI, speech bubbles, watermark, gore, photorealism."));
    }

    public List<String> toStringList(JsonNode arrayNode) {
        List<String> list = new ArrayList<>();
        arrayNode.forEach(node -> list.add(node.asText()));
        return list;
    }
}

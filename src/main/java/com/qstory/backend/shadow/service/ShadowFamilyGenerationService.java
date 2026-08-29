package com.qstory.backend.shadow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.common.enums.ReviewStatus;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.common.error.ProviderException;
import com.qstory.backend.common.util.JacksonConversion;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.common.util.SupabaseStorageClient;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.familydraft.util.FamilyDraftHarness;
import com.qstory.backend.provider.ProviderReadiness;
import com.qstory.backend.provider.openrouter.util.OpenRouterClient;
import com.qstory.backend.shadow.entity.ShadowFamilyDraft;
import com.qstory.backend.shadow.entity.ShadowIntentCandidate;
import com.qstory.backend.shadow.repository.ShadowFamilyDraftRepository;
import com.qstory.backend.shadow.repository.ShadowIntentRepository;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * fe/q-story-beta-player-main/server/src/shadow-generation.mjs + scripts/generate-shadow-family.mjs를
 * Java로 포팅한 것. candidate 수집(ShadowIntentCollectionService)·사람 검수(ShadowReviewService)는
 * 이미 이식되어 있었고, 이 서비스가 빠져 있던 마지막 조각이다: 승인된 candidate로부터 실제
 * 대본·삽화·오디오 초안을 만든다.
 *
 * <p>제품 결정: draft 자체의 검수는 사람 없이 자동 LLM 게이트(reviewDraft 동등물)만 통과하면 바로
 * APPROVED로 저장한다. candidate 승인(사람이 "이 반복 질문은 콘텐츠로 만들 가치가 있다"고 판단하는
 * 단계, ShadowReviewController)은 그대로 사람이 한다 - 이 서비스는 candidate.reviewStatus가 이미
 * APPROVED일 때만 호출할 수 있다.
 */
@Service
public class ShadowFamilyGenerationService {

    private static final int MAX_ATTEMPTS = 3;
    private static final String PROMPT_VERSION = "QSTORY_SHADOW_FAMILY_V1_JAVA";
    private static final Set<String> EXISTING_FAMILY_IDS = Set.of(
            "A_OBSERVE_BIRD", "A_SPEAK_TO_BIRD", "A_CHECK_SURROUNDINGS", "A_TRY_OTHER_PATH",
            "B_ASK_OLD_WOMAN", "B_CHECK_KEYS", "B_CHECK_HOUSE", "B_STEP_BACK_MARK_EXIT", "B_MAKE_SIBLING_SIGNAL",
            "C_ASK_DEMONSTRATION", "C_DISTRACT_AND_TAKE_KEYS", "C_USE_SIGNAL", "C_CHECK_LOCK_FROM_DISTANCE",
            "C_BLOCK_PURSUIT_SAFELY");

    private record AnchorContract(
            String slot, String sceneSummary, List<String> allowedCharacters, String location,
            List<String> rejoinAnchorIds, List<String> existingFamilies, String referenceResourcePath) {}

    private static final Map<String, AnchorContract> ANCHOR_CONTRACTS = Map.of(
            "HG-Q-A", new AnchorContract(
                    "A",
                    "헨젤과 그레텔이 숲에서 길을 잃었고, 낮은 나뭇가지의 하얀 새가 두 아이를 보며 움직인다.",
                    List.of("HANSEL", "GRETEL", "WHITE_BIRD"), "FOREST",
                    List.of("HG-F04-CANDY-HOUSE-REVEAL"),
                    List.of("새 관찰", "새에게 말 걸기", "주변 단서 확인", "반대쪽 길 확인"),
                    "shadow-reference/HG-Q-A.jpg"),
            "HG-Q-B", new AnchorContract(
                    "B",
                    "남매가 과자집 문 앞에 있고 노파가 열쇠고리를 든 채 문을 열었다. 아이들은 노파가 마녀인지 아직 모른다.",
                    List.of("HANSEL", "GRETEL", "OLD_WOMAN"), "CANDY_EXTERIOR",
                    List.of("HG-F05-ENTER-HOUSE", "HG-F06-CAPTURED-STATE"),
                    List.of("노파에게 묻기", "열쇠 확인", "창문·문틀 확인", "물러나 길 확인", "남매 신호 정하기"),
                    "shadow-reference/HG-Q-B.jpg"),
            "HG-Q-C", new AnchorContract(
                    "C",
                    "마녀가 열쇠고리를 쥔 채 화덕에서 멀리 서서 그레텔에게만 안을 보라고 명령한다. 헨젤은 손에 무기나 도구 없이 쇠창살 안에 있다.",
                    List.of("HANSEL", "GRETEL", "WITCH"), "CANDY_INTERIOR",
                    List.of("HG-F07-DEMONSTRATION", "HG-F08-AFTER-BRANCH-ESCAPE"),
                    List.of("시범 요청", "냄비로 시선을 돌리고 열쇠 확보", "두 번 두드리기 신호", "긴 주걱으로 잠금 확인", "사탕 철문으로 추격 지연"),
                    "shadow-reference/HG-Q-C.jpg"));

    private final ShadowIntentRepository candidateRepository;
    private final ShadowFamilyDraftRepository draftRepository;
    private final OpenRouterClient openRouterClient;
    private final SupabaseStorageClient storageClient;
    private final ObjectMapper objectMapper;
    private final AppProperties config;
    private final FamilyDraftHarness harness;

    public ShadowFamilyGenerationService(
            ShadowIntentRepository candidateRepository, ShadowFamilyDraftRepository draftRepository,
            OpenRouterClient openRouterClient, SupabaseStorageClient storageClient, ObjectMapper objectMapper,
            AppProperties config, FamilyDraftHarness harness) {
        this.candidateRepository = candidateRepository;
        this.draftRepository = draftRepository;
        this.openRouterClient = openRouterClient;
        this.storageClient = storageClient;
        this.objectMapper = objectMapper;
        this.config = config;
        this.harness = harness;
    }

    @Transactional
    public ShadowFamilyDraft generateDraft(UUID candidateId) {
        if (!ProviderReadiness.of(config).llm() || !ProviderReadiness.of(config).tts()
                || !ProviderReadiness.of(config).image() || !config.supabase().configured()) {
            throw ApiException.contractError(ErrorCode.INTERNAL_ERROR, "초안 생성 준비가 아직 끝나지 않았어요.", 500);
        }
        ShadowIntentCandidate candidate = candidateRepository.findCandidateById(candidateId)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "그 후보를 찾지 못했어요.", 404));
        if (candidate.getReviewStatus() != ReviewStatus.APPROVED) {
            throw ApiException.contractError(
                    ErrorCode.VALIDATION_FAILED, "사람이 승인한 후보만 초안을 만들 수 있어요.", 400);
        }
        AnchorContract contract = ANCHOR_CONTRACTS.get(candidate.getAnchorId());
        if (contract == null) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "지원하지 않는 질문 위치예요.", 400);
        }

        // 이 파이프라인은 실시간 아동 응답 경로가 아니라 STAFF가 트리거하는 오프라인 생성이다 -
        // requestTimeoutMs(기본 40초)는 실시간 라우팅용 예산이라, 대본→검수→이미지→오디오 여러
        // 단계 전체에 하나의 데드라인을 공유하면 뒤쪽 단계가 시간 부족으로 죽는다. 호출마다
        // 새 데드라인을 준다.
        String safeIntent = harness.redact(candidate.getRepresentativeIntent(), 160);
        JsonNode draft = null;
        List<String> revisionFeedback = List.of();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            JsonNode attemptDraft = requestDraft(
                    contract, safeIntent, candidate, revisionFeedback, freshDeadline());
            String issue = validationIssue(attemptDraft, contract);
            if (issue != null) {
                revisionFeedback = List.of(issue);
                continue;
            }
            List<String> reviewIssues = reviewGateIssues(contract, attemptDraft, freshDeadline());
            if (!reviewIssues.isEmpty()) {
                revisionFeedback = reviewIssues;
                continue;
            }
            draft = attemptDraft;
            break;
        }
        if (draft == null) {
            throw new ProviderException(
                    ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, "초안이 검수 기준을 통과하지 못했어요.");
        }

        byte[] referenceBytes = readReferenceImage(contract.referenceResourcePath());
        OpenRouterClient.GeneratedImage image = openRouterClient.generateImage(
                buildImagePrompt(draft), referenceBytes, "image/jpeg", freshDeadline());
        String narrationText = narrationPreviewText(draft);
        var audio = openRouterClient.synthesize(
                narrationText, config.providers().openRouter().ttsVoice(), 1.0, freshDeadline());

        String imageExtension = switch (image.mimeType()) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            default -> "webp";
        };
        String imageObjectName = candidateId + "/draft-v1." + imageExtension;
        String audioObjectName = candidateId + "/preview-v1.wav";
        String bucket = config.supabase().shadowAssetsBucket();
        if (!storageClient.upload(bucket, imageObjectName, image.bytes(), image.mimeType())
                || !storageClient.upload(bucket, audioObjectName, audio.audio(), audio.mimeType())) {
            storageClient.delete(bucket, imageObjectName);
            storageClient.delete(bucket, audioObjectName);
            throw ApiException.contractError(ErrorCode.INTERNAL_ERROR, "초안 자산을 저장하지 못했어요.", 500);
        }

        ShadowFamilyDraft entity = draftRepository.findByCandidate_Id(candidateId).orElseGet(ShadowFamilyDraft::new);
        entity.setCandidate(candidate);
        entity.setProposedFamilyId(draft.path("proposedFamilyId").asText());
        entity.setTitle(draft.path("title").asText());
        entity.setIntentSummary(draft.path("intentSummary").asText());
        entity.setRationale(draft.path("rationale").asText());
        entity.setAcknowledgementText(draft.path("acknowledgementText").asText());
        entity.setEntryState(draft.path("entryState").asText());
        entity.setExitState(draft.path("exitState").asText());
        entity.setRejoinAnchorId(draft.path("rejoinAnchorId").asText());
        entity.setReportSummary(draft.path("reportSummary").asText());
        entity.setChoiceCopy(toListOfMaps(draft.path("choiceCopy")));
        entity.setBeats(toListOfMaps(draft.path("beats")));
        entity.setImageBrief(toMap(draft.path("imageBrief")));
        entity.setImageObjectName(imageObjectName);
        entity.setAudioObjectName(audioObjectName);
        entity.setImageMimeType(image.mimeType());
        entity.setAudioMimeType(audio.mimeType());
        entity.setPromptVersion(PROMPT_VERSION);
        entity.setLlmModel(config.providers().openRouter().llmModel());
        entity.setImageModel(config.providers().openRouter().imageModel());
        entity.setTtsModel(config.providers().openRouter().ttsModel());
        entity.setGeneratedAt(Instant.now());
        // 제품 결정: 사람 검수 없이 위 자동 게이트(reviewGateIssues) 통과만으로 바로 승인한다.
        entity.setReviewStatus(ReviewStatus.APPROVED);
        entity.setReviewedAt(Instant.now());
        entity.setReviewNote("auto-approved: passed the automated LLM review gate, no human review step");
        return draftRepository.save(entity);
    }

    /** 이미지 생성이 특히 느릴 수 있어 실시간 라우팅용 기본값보다 여유 있게 잡는다. */
    private RequestDeadline freshDeadline() {
        return harness.freshDeadline(config.requestTimeoutMs());
    }

    private JsonNode requestDraft(
            AnchorContract contract, String safeIntent, ShadowIntentCandidate candidate,
            List<String> revisionFeedback, RequestDeadline deadline) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("candidateId", candidate.getId().toString());
        payload.put("repeatedIntent", safeIntent);
        payload.put("occurrenceCount", candidate.getOccurrenceCount());
        payload.put("distinctSessionCount", candidate.getDistinctSessionCount());
        payload.put("slot", contract.slot());
        payload.put("currentScene", contract.sceneSummary());
        ArrayNode existingFamilies = payload.putArray("existingFamilies");
        contract.existingFamilies().forEach(existingFamilies::add);
        ArrayNode allowedCharacters = payload.putArray("allowedCharacters");
        contract.allowedCharacters().forEach(allowedCharacters::add);
        payload.put("fixedLocation", contract.location());
        ArrayNode rejoinIds = payload.putArray("allowedRejoinAnchorIds");
        contract.rejoinAnchorIds().forEach(rejoinIds::add);
        if (!revisionFeedback.isEmpty()) {
            ArrayNode feedback = payload.putArray("revisionFeedback");
            revisionFeedback.forEach(feedback::add);
            payload.put("revisionInstruction", "앞선 초안의 탈락 이유를 수정한 완전히 새로운 초안을 쓰라.");
        }

        return openRouterClient.generateStructuredCompletion(
                draftSystemPrompt(), payload.toString(), draftSchema(contract), "qstory_shadow_family_draft_v1",
                2_400, ProviderErrorCode.OPENROUTER_RESPONSE_INVALID, "초안을 만들지 못했어요.", deadline);
    }

    private List<String> reviewGateIssues(AnchorContract contract, JsonNode draft, RequestDeadline deadline) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("anchorId", contract.slot());
        return harness.reviewGateIssues(
                payload, draft, reviewSystemPrompt(), "qstory_shadow_family_review_v1",
                "초안 검수를 완료하지 못했어요.", deadline);
    }

    /**
     * shadow-generation.mjs의 validateShadowDraft()를 이식한 것 - 통과하면 null, 아니면 재시도 사유
     * 문자열. proposedFamilyId/imageBrief.location/B슬롯 안전성 검사는 Shadow 고유(LiveBranch에는 해당
     * 개념이 없다) 라 여기 그대로 두고, 나머지 공통 검사는 FamilyDraftHarness에 위임한다 - 검사 순서는
     * 기존 그대로 유지해 여러 문제가 동시에 있는 초안에서 재시도 피드백으로 돌아가는 "첫 실패 사유"가
     * 바뀌지 않게 한다.
     */
    private String validationIssue(JsonNode draft, AnchorContract contract) {
        String proposedFamilyId = draft.path("proposedFamilyId").asText("");
        if (!proposedFamilyId.matches("^SHADOW_[ABC]_[A-Z0-9_]{3,50}$")
                || !proposedFamilyId.startsWith("SHADOW_" + contract.slot() + "_")
                || EXISTING_FAMILY_IDS.contains(proposedFamilyId)) {
            return "허용된 접두어의 새 family ID를 쓰라 - 기존 family와 겹치지 않아야 한다.";
        }
        String rejoinIssue = harness.rejoinAnchorIssue(draft, contract.rejoinAnchorIds());
        if (rejoinIssue != null) {
            return rejoinIssue;
        }
        JsonNode imageBrief = draft.path("imageBrief");
        if (!contract.location().equals(imageBrief.path("location").asText(""))) {
            return "imageBrief.location은 입력의 fixedLocation과 같아야 한다.";
        }
        // Shadow의 imageBrief.characters/dialogue.speaker는 ANCHOR_CONTRACTS에 하드코딩된 bare label
        // (예: "GRETEL")로 채워진다 - contract.allowedCharacters()가 그 실제 형식이다. LiveBranch의
        // anchor.getAllowedSpeakerIds()(예: "HG-SPK-GRETEL")와는 다른 표현이지만, 그건 LiveBranch의
        // draft가 실제로 그 형식으로 채워지기 때문이다(StoryCast.speakerId 문서, draftSchema의
        // enum 참고) - 여기서 그 형식으로 정규화하면 오히려 틀린다.
        String charactersIssue = harness.imageCharactersIssue(draft, contract.allowedCharacters());
        if (charactersIssue != null) {
            return charactersIssue;
        }
        String choiceCopyIssue = harness.choiceCopyIssue(draft);
        if (choiceCopyIssue != null) {
            return choiceCopyIssue;
        }
        String beatsIssue = harness.beatsIssue(draft, contract.allowedCharacters());
        if (beatsIssue != null) {
            return beatsIssue;
        }
        String reportSummaryIssue = harness.reportSummaryIssue(draft);
        if (reportSummaryIssue != null) {
            return reportSummaryIssue;
        }
        if ("B".equals(contract.slot())) {
            String fullText = draft.toString();
            Pattern falseSafety = Pattern.compile("마음을?\\s*놓|위험하지\\s*않|안전하다고\\s*결론|위험한\\s*소리는?\\s*안|수상한\\s*소리가\\s*나지\\s*않|이상한\\s*소리가\\s*나지\\s*않");
            if (falseSafety.matcher(fullText).find()) {
                return "감각 확인만으로 안전하다고 결론 내리지 마라 - 남매는 계속 주의를 유지해야 한다.";
            }
        }
        return null;
    }

    private byte[] readReferenceImage(String resourcePath) {
        try (InputStream stream = new ClassPathResource(resourcePath).getInputStream()) {
            return stream.readAllBytes();
        } catch (IOException error) {
            throw ApiException.contractError(ErrorCode.INTERNAL_ERROR, "참조 삽화를 불러오지 못했어요.", 500);
        }
    }

    private String buildImagePrompt(JsonNode draft) {
        JsonNode imageBrief = draft.path("imageBrief");
        List<String> characters = harness.toStringList(imageBrief.path("characters"));
        String locationLine = "Location: " + imageBrief.path("location").asText() + ".";
        return String.join("\n", harness.baseImagePromptLines(characters, locationLine, imageBrief));
    }

    private String narrationPreviewText(JsonNode draft) {
        StringBuilder builder = new StringBuilder(draft.path("acknowledgementText").asText());
        for (JsonNode beat : draft.path("beats")) {
            builder.append(' ').append(beat.path("narratorText").asText());
            for (JsonNode line : beat.path("dialogue")) {
                builder.append(' ').append(line.path("text").asText());
            }
        }
        String joined = builder.toString().replaceAll("\\s+", " ").trim();
        return joined.length() > 1800 ? joined.substring(0, 1800) : joined;
    }

    private List<Map<String, Object>> toListOfMaps(JsonNode node) {
        return JacksonConversion.toListOfMaps(objectMapper, node);
    }

    private Map<String, Object> toMap(JsonNode node) {
        return JacksonConversion.toMap(objectMapper, node);
    }

    private ObjectNode draftSchema(AnchorContract contract) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        harness.stringProp(properties, "proposedFamilyId", 8, 64);
        harness.stringProp(properties, "title", 2, 40);
        harness.stringProp(properties, "intentSummary", 2, 160);
        harness.stringProp(properties, "rationale", 10, 300);
        harness.stringProp(properties, "acknowledgementText", 5, 120);

        ObjectNode choiceCopy = properties.putObject("choiceCopy");
        choiceCopy.put("type", "array");
        choiceCopy.put("minItems", 3);
        choiceCopy.put("maxItems", 3);
        ObjectNode choiceCopyItem = choiceCopy.putObject("items");
        choiceCopyItem.put("type", "object");
        choiceCopyItem.put("additionalProperties", false);
        ObjectNode choiceCopyProps = choiceCopyItem.putObject("properties");
        harness.stringProp(choiceCopyProps, "label", 3, 30);
        harness.stringProp(choiceCopyProps, "meaning", 8, 100);
        choiceCopyItem.putArray("required").add("label").add("meaning");

        harness.stringProp(properties, "entryState", 5, 200);

        ObjectNode beats = properties.putObject("beats");
        beats.put("type", "array");
        beats.put("minItems", 1);
        beats.put("maxItems", 2);
        ObjectNode beatItem = beats.putObject("items");
        beatItem.put("type", "object");
        beatItem.put("additionalProperties", false);
        ObjectNode beatProps = beatItem.putObject("properties");
        harness.stringProp(beatProps, "action", 5, 120);
        harness.stringProp(beatProps, "narratorText", 10, 300);
        ObjectNode dialogue = beatProps.putObject("dialogue");
        dialogue.put("type", "array");
        dialogue.put("minItems", 0);
        dialogue.put("maxItems", 2);
        ObjectNode dialogueItem = dialogue.putObject("items");
        dialogueItem.put("type", "object");
        dialogueItem.put("additionalProperties", false);
        ObjectNode dialogueProps = dialogueItem.putObject("properties");
        dialogueProps.putObject("speaker").put("type", "string");
        harness.stringProp(dialogueProps, "text", 2, 120);
        dialogueItem.putArray("required").add("speaker").add("text");
        beatItem.putArray("required").add("action").add("narratorText").add("dialogue");

        harness.stringProp(properties, "exitState", 5, 200);
        properties.putObject("rejoinAnchorId").put("type", "string");
        harness.stringProp(properties, "reportSummary", 10, 220);

        ObjectNode imageBrief = properties.putObject("imageBrief");
        imageBrief.put("type", "object");
        imageBrief.put("additionalProperties", false);
        ObjectNode imageBriefProps = imageBrief.putObject("properties");
        ObjectNode characters = imageBriefProps.putObject("characters");
        characters.put("type", "array");
        characters.put("minItems", 1);
        characters.put("maxItems", 3);
        characters.putObject("items").put("type", "string");
        imageBriefProps.putObject("location").put("type", "string");
        harness.stringProp(imageBriefProps, "action", 5, 160);
        harness.stringProp(imageBriefProps, "composition", 5, 200);
        ObjectNode continuityFacts = imageBriefProps.putObject("continuityFacts");
        continuityFacts.put("type", "array");
        continuityFacts.put("minItems", 2);
        continuityFacts.put("maxItems", 6);
        continuityFacts.putObject("items").put("type", "string");
        harness.stringProp(imageBriefProps, "negativePrompt", 10, 300);
        imageBrief.putArray("required")
                .add("characters").add("location").add("action").add("composition")
                .add("continuityFacts").add("negativePrompt");

        schema.putArray("required")
                .add("proposedFamilyId").add("title").add("intentSummary").add("rationale")
                .add("acknowledgementText").add("choiceCopy").add("entryState").add("beats")
                .add("exitState").add("rejoinAnchorId").add("reportSummary").add("imageBrief");
        return schema;
    }

    private String draftSystemPrompt() {
        return String.join("\n",
                "너는 6~9세용 검수 동화의 비공개 신규 family 초안 작가다.",
                "아이의 반복된 질문 의미를 현재 장면에서 직접 느낄 수 있는 1~2개 행동 beat로 바꿔라.",
                "repeatedIntent의 핵심 감각·목적·행동을 다른 단서로 바꾸지 말고 결과 beat에 직접 보여주라.",
                "proposedFamilyId는 반드시 SHADOW_<slot>_<UPPER_SNAKE_CASE>로 쓰라.",
                "기존 family의 단순 동의어나 소품만 바꾼 복제본은 만들지 마라.",
                "choiceCopy 3개는 서로 다른 행동이 아니라 하나의 고정 beat를 정확히 다른 말로 표현한 검수 문구 3세트여야 한다.",
                "현재 장면에 없는 물건·능력·약속을 갑자기 만들지 마라. 필요하면 해당 family 안에서 먼저 설명하라.",
                "입력의 currentScene에 없는 발자국·도구·숨은 문·마법 소품 같은 새 증거를 즉석에서 추가하지 마라.",
                "달콤한 냄새·조용한 소리·친절한 말만으로 상대나 장소가 안전하다고 결론내지 마라. 단서를 확인해도 남매는 주의를 유지해야 한다.",
                "냄새·소리로 안전을 묻는 의도라면 감각 확인 자체를 막지 말고, 그 결과만으로는 안전을 알 수 없다는 것을 사건 결과로 보여주라.",
                "소리가 필요하면 새로운 찌물·장작·발자국을 만들지 말고, 현재 문틈 밖에서 귀를 기울여도 안쪽을 확실히 알 수 없다는 결과를 쓰라.",
                "아동이 따라 할 수 있는 구체적 폭력·화덕 상해·위험 도구 사용을 쓰지 마라.",
                "인물·장소·합류점은 입력으로 주어진 허용 목록만 사용하라.",
                "삽화에는 한 beat의 핵심 행동만 보이게 하고 글자·UI·말풍선은 넣지 마라.",
                "이 결과는 사람 검수 없이 자동 게이트만 통과하면 곧바로 아이에게 노출될 수 있으니 더욱 신중하게 작성하라.",
                "reportSummary는 생성 과정이 아니라 아이가 고른 행동과 그 결과를 부모에게 요약하라.");
    }

    private String reviewSystemPrompt() {
        return String.join("\n",
                "너는 아동 검수 동화의 초안 게이트다.",
                "반복 의미와 직접 연결되지 않거나, currentScene에 없는 소품·증거·약속을 만들거나, 달콤한 냄새·조용한 소리·친절한 말만으로 안전을 결론내면 탈락이다.",
                "반복 의미가 냄새·소리로 안전을 확인하고 싶다는 것이라면, 그 감각을 실제로 살피되 그것만으로는 안전을 알 수 없다고 남는 초안은 의도 정렬과 안전 모두 통과할 수 있다.",
                "choiceCopy 3개가 하나의 고정 beat의 엄격한 바꿔쓰기가 아니거나, 대본·삽화 action·exitState·rejoin이 서로 다르면 탈락이다.",
                "인물·장소·시점·소품·아동 안전을 모두 검사하고, 모든 점수가 2일 때만 pass=true로 하라.");
    }
}

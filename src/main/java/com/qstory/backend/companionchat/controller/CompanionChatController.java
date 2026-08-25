package com.qstory.backend.companionchat.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.error.FailureBody;
import com.qstory.backend.common.util.HttpBodyReader;
import com.qstory.backend.common.util.HttpJsonWriter;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.companionchat.repository.CompanionChatTurnRepository;
import com.qstory.backend.companionchat.service.CompanionChatPipelineService;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.question.util.QuestionContractValidator;
import com.qstory.backend.story.service.StoryRegistryService;
import com.qstory.backend.story.service.StoryRegistryService.ResolvedCompanionContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 항상 켜져 있는, 앵커와 무관한 채팅 창구 - QuestionController의 앵커 기반 라우팅
 * 엔드포인트와는 분리되어 있는데, 이 경로에는 분기/패밀리 개념이 전혀 없고 오직
 * 따뜻한 캐릭터 응답만 있기 때문이다. 응답 형태는 CompanionChatPipelineService를 참고.
 */
@Tag(name = "CompanionChat", description = "Anchor-independent in-character chat, distinct from the branch-triggering question flow")
@RestController
public class CompanionChatController {

    private static final long RATE_LIMIT_MAX_TURNS = 40;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(10);

    private final AppProperties config;
    private final ObjectMapper objectMapper;
    private final QuestionContractValidator contractValidator;
    private final StoryRegistryService storyRegistryService;
    private final CompanionChatPipelineService pipeline;
    private final CompanionChatTurnRepository turnRepository;
    private final CurrentUserResolver currentUserResolver;

    public CompanionChatController(
            AppProperties config, ObjectMapper objectMapper, QuestionContractValidator contractValidator,
            StoryRegistryService storyRegistryService, CompanionChatPipelineService pipeline,
            CompanionChatTurnRepository turnRepository, CurrentUserResolver currentUserResolver) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.contractValidator = contractValidator;
        this.storyRegistryService = storyRegistryService;
        this.pipeline = pipeline;
        this.turnRepository = turnRepository;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(
            summary = "Send one companion-chat message",
            description = "Anchor-independent free chat with the story's character - never branches the story. "
                    + "Body: {storyId, sceneId, conversationId, transcript}. The transcript is never persisted; "
                    + "only a derived topic/tone/value tag set is (see CompanionChatTurn).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "In-character reply plus its synthesized audio"),
            @ApiResponse(responseCode = "400", description = "Malformed body",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "429", description = "Too many turns in this conversation window",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "502", description = "LLM/TTS provider call failed",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "{storyId, sceneId, conversationId, transcript}", required = true)
    @PostMapping("/v1/companion-chat/messages")
    public void sendMessage(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonNode body = HttpBodyReader.readJsonBody(request, objectMapper);
        String storyId = requireText(body, "storyId");
        String sceneId = requireText(body, "sceneId");
        UUID conversationId = requireUuid(body, "conversationId");
        String transcript = requireText(body, "transcript");

        long recentTurns = turnRepository.countByConversationIdAndOccurredAtAfter(
                conversationId, Instant.now().minus(RATE_LIMIT_WINDOW));
        if (recentTurns >= RATE_LIMIT_MAX_TURNS) {
            throw ApiException.contractError(
                    ErrorCode.COMPANION_CHAT_RATE_LIMITED, "지금은 대화를 너무 많이 나눴어요. 잠시 후 다시 말을 걸어주세요.");
        }

        ResolvedCompanionContext context = storyRegistryService.resolveCompanionChatContext(
                storyId, sceneId, currentUserResolver.current().orElse(null));
        Map<String, Object> result = pipeline.respond(
                context, conversationId, transcript, RequestDeadline.startingNow(config.requestTimeoutMs()));
        HttpJsonWriter.writeJson(response, objectMapper, 200, result);
    }

    @Operation(
            summary = "Transcribe a base64-encoded audio recording for companion chat",
            description = "JSON body {audioBase64, mimeType, storyId, sceneId} - the anchor-free counterpart of "
                    + "POST /v1/transcriptions/base64. No anchorId/questionRound field (companion chat never "
                    + "branches).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transcript"),
            @ApiResponse(responseCode = "400", description = "Malformed base64, empty audio, or invalid/missing context fields",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "413", description = "Decoded audio exceeds qstory.max-audio-bytes",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "502", description = "STT provider call failed",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "{audioBase64, mimeType, storyId, sceneId}", required = true)
    @PostMapping("/v1/companion-chat/transcriptions/base64")
    public void transcribeBase64(
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpBodyReader.DecodedAudio decoded =
                HttpBodyReader.readBase64AudioBody(request, objectMapper, config.maxAudioBytes());
        QuestionContractValidator.CompanionAudioContext header =
                contractValidator.parseCompanionAudioContextFromBody(decoded.body(), decoded.mimeType());
        ResolvedCompanionContext context = storyRegistryService.resolveCompanionChatContext(
                header.storyId(), header.sceneId(), currentUserResolver.current().orElse(null));
        Map<String, Object> result = pipeline.transcribe(
                context, header.sourceMimeType(), decoded.audio(), RequestDeadline.startingNow(config.requestTimeoutMs()));
        HttpJsonWriter.writeJson(response, objectMapper, 200, result);
    }

    private String requireText(JsonNode body, String field) {
        String value = body != null && body.path(field).isTextual() ? body.get(field).asText().trim() : "";
        if (value.isEmpty()) {
            throw ApiException.contractError(
                    ErrorCode.INVALID_COMPANION_CHAT_REQUEST, "대화 요청 형식을 읽지 못했어요.");
        }
        return value;
    }

    private UUID requireUuid(JsonNode body, String field) {
        String raw = requireText(body, field);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw ApiException.contractError(
                    ErrorCode.INVALID_COMPANION_CHAT_REQUEST, "대화 요청 형식을 읽지 못했어요.");
        }
    }
}

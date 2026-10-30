package com.qstory.backend.companionchat.controller;

import com.qstory.backend.companionchat.DialogueInput;
import com.qstory.backend.story.Anchor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.error.FailureBody;
import com.qstory.backend.common.util.HttpBodyReader;
import com.qstory.backend.common.util.HttpJsonWriter;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.common.util.ValidationSupport;
import com.qstory.backend.companionchat.repository.CompanionChatTurnRepository;
import com.qstory.backend.companionchat.service.CompanionChatPipelineService;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.common.enums.ConversationInputMode;
import com.qstory.backend.conversationrecord.ConversationAttribution;
import com.qstory.backend.conversationrecord.util.ConversationAttributionParser;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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

    private static final int NEWLINE = 0x0A;
    private static final long RATE_LIMIT_MAX_TURNS = 40;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(10);

    private final AppProperties config;
    private final ObjectMapper objectMapper;
    private final QuestionContractValidator contractValidator;
    private final StoryRegistryService storyRegistryService;
    private final CompanionChatPipelineService pipeline;
    private final CompanionChatTurnRepository turnRepository;
    private final CurrentUserResolver currentUserResolver;
    private final ConversationAttributionParser attributionParser;
    private final boolean replyStreaming;

    public CompanionChatController(
            AppProperties config, ObjectMapper objectMapper, QuestionContractValidator contractValidator,
            StoryRegistryService storyRegistryService, CompanionChatPipelineService pipeline,
            CompanionChatTurnRepository turnRepository, CurrentUserResolver currentUserResolver,
            ConversationAttributionParser attributionParser,
            @Value("${qstory.voice-streaming.companion-reply:true}") boolean replyStreaming) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.contractValidator = contractValidator;
        this.storyRegistryService = storyRegistryService;
        this.pipeline = pipeline;
        this.turnRepository = turnRepository;
        this.currentUserResolver = currentUserResolver;
        this.attributionParser = attributionParser;
        this.replyStreaming = replyStreaming;
    }

    // QStory Extension Kit: StoryPal 나이대를 아이 프로필에서 채운다. 없으면(테스트 등) 요청 그대로.
    private com.qstory.backend.extension.learning.LearningAgeResolver learningAgeResolver;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLearningAgeResolver(com.qstory.backend.extension.learning.LearningAgeResolver resolver) {
        this.learningAgeResolver = resolver;
    }

    @Operation(
            summary = "Send one companion-chat message",
            description = "Anchor-independent free chat with the story's character - never branches the story. "
                    + "Body: {storyId, sceneId, conversationId, transcript, speakerId?}. speakerId is the cast "
                    + "member the child is talking to (e.g. HG-SPK-GRETEL) - its persona sheet (story_persona, "
                    + "imported from personas.yaml) and voice are used; omitted, the scene's anchor speaker or the "
                    + "narrator is used. Besides the derived topic/tone/value tags (CompanionChatTurn), the transcript "
                    + "and reply are appended to the write-only conversation_record log (db/schema/052). "
                    + "With deferAudio=true and stream=true (and qstory.voice-streaming.companion-reply on) the 200 body is "
                    + "NDJSON (application/x-ndjson): first {type:text, responseText} as soon as the reply sentence is "
                    + "ready, then {type:result, ...the usual JSON body...} - a failure is that last line with ok=false. "
                    + "Otherwise the body is the usual single JSON object.")
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
            description = "{storyId, sceneId, conversationId, transcript, speakerId?, childId?, tutorStudentId?, lessonId?, inputMode?, "
                    + "history?: [{role: CHILD|CHARACTER, text}], scene?: {title, storySoFar[], recentLines[], visual}, "
                    + "executedActions?: [], anchorId? (question invite), wrapUp?: NONE|SUGGEST_RETURN|CLOSE, "
                    + "help?: {step, total, hint} (help button inside a question invite - reply follows the hint in context), "
                    + "deferAudio?: boolean (true: no audio in the reply, audioDeferred=true - fetch it from POST /v1/narrations/stream), "
                    + "stream?: boolean (with deferAudio: NDJSON body, reply sentence first)}",
                    required = true)
    @PostMapping("/v1/companion-chat/messages")
    public void sendMessage(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonNode body = HttpBodyReader.readJsonBody(request, objectMapper);
        String storyId = requireText(body, "storyId");
        String sceneId = requireText(body, "sceneId");
        UUID conversationId = requireUuid(body, "conversationId");
        String transcript = requireText(body, "transcript");
        String speakerId = body.path("speakerId").isTextual() ? body.path("speakerId").asText() : null;

        long recentTurns = turnRepository.countByConversationIdAndOccurredAtAfter(
                conversationId, Instant.now().minus(RATE_LIMIT_WINDOW));
        if (recentTurns >= RATE_LIMIT_MAX_TURNS) {
            throw ApiException.contractError(
                    ErrorCode.COMPANION_CHAT_RATE_LIMITED, "지금은 대화를 너무 많이 나눴어요. 잠시 후 다시 말을 걸어주세요.");
        }

        var caller = currentUserResolver.currentOrNull();
        ResolvedCompanionContext context = storyRegistryService.resolveCompanionChatContext(
                storyId, sceneId, speakerId, caller);
        ConversationAttribution attribution = attributionParser.fromBody(body, ConversationInputMode.TEXT, caller);
        DialogueInput dialogue = DialogueInput.fromBody(body);
        if (learningAgeResolver != null) {
            var learning = learningAgeResolver.apply(dialogue.learning(), attribution);
            if (learning != dialogue.learning()) {
                dialogue = new DialogueInput(dialogue.history(), dialogue.scene(), dialogue.executedActions(),
                        dialogue.anchorId(), dialogue.wrapUp(), dialogue.help(), dialogue.attempt(), learning);
            }
        }
        Anchor inviteAnchor = dialogue.isInvite()
                ? storyRegistryService.inviteAnchor(context.story(), dialogue.anchorId(), sceneId)
                : null;
        boolean deferAudio = body.path("deferAudio").asBoolean(false);
        RequestDeadline deadline = RequestDeadline.startingNow(config.requestTimeoutMs());
        if (deferAudio && replyStreaming && body.path("stream").asBoolean(false)) {
            streamReply(response, context, conversationId, transcript, dialogue, inviteAnchor, deadline, attribution);
            return;
        }
        Map<String, Object> result = pipeline.respond(
                context, conversationId, transcript, dialogue, inviteAnchor, deferAudio, deadline, attribution);
        HttpJsonWriter.writeJson(response, objectMapper, 200, result);
    }

    /**
     * 답 문장을 먼저 한 줄({type:text}), 끝나면 평소 응답 본문을 한 줄({type:result, ...}) 내려보낸다(NDJSON).
     * 클라이언트는 첫 줄로 말풍선을 띄우고 같은 문장의 음성(POST /v1/narrations/stream)을 바로 요청한다 - 답의
     * 나머지(태그·replyKind 등)를 기다리는 동안 음성이 만들어진다. 헤더를 먼저 보내므로 실패도 200 + 마지막 줄 ok=false다
     * (이 엔드포인트의 기존 실패 봉투와 같은 모양). 아이가 창을 닫아 쓰기가 실패해도 답은 끝까지 받아 기록에 남긴다.
     */
    private void streamReply(
            HttpServletResponse response, ResolvedCompanionContext context, UUID conversationId, String transcript,
            DialogueInput dialogue, Anchor inviteAnchor, RequestDeadline deadline, ConversationAttribution attribution) {
        response.setStatus(200);
        response.setContentType("application/x-ndjson; charset=utf-8");
        response.setHeader("Cache-Control", "no-store, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        boolean[] clientGone = {false};
        Map<String, Object> result = pipeline.respond(
                context, conversationId, transcript, dialogue, inviteAnchor, true, deadline, attribution,
                text -> writeLine(response, Map.of("type", "text", "responseText", text), clientGone));
        Map<String, Object> last = new LinkedHashMap<>();
        last.put("type", "result");
        last.putAll(result);
        writeLine(response, last, clientGone);
    }

    private void writeLine(HttpServletResponse response, Map<String, Object> value, boolean[] clientGone) {
        if (clientGone[0]) {
            return;
        }
        try {
            var out = response.getOutputStream();
            out.write(objectMapper.writeValueAsBytes(value));
            out.write(NEWLINE);
            out.flush();
        } catch (IOException gone) {
            clientGone[0] = true;
        }
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
        var caller = currentUserResolver.currentOrNull();
        ResolvedCompanionContext context = storyRegistryService.resolveCompanionChatContext(
                header.storyId(), header.sceneId(), null, caller);
        ConversationAttribution attribution = attributionParser.fromBody(decoded.body(), ConversationInputMode.VOICE, caller);
        Map<String, Object> result = pipeline.transcribe(
                context, header.sourceMimeType(), decoded.audio(), RequestDeadline.startingNow(config.requestTimeoutMs()), attribution);
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
        return ValidationSupport.parseUuid(
                raw, ErrorCode.INVALID_COMPANION_CHAT_REQUEST, "대화 요청 형식을 읽지 못했어요.");
    }
}

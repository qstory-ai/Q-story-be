package com.qstory.backend.sttstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.enums.ConversationInputMode;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.error.FailureBody;
import com.qstory.backend.common.util.HttpBodyReader;
import com.qstory.backend.common.util.HttpJsonWriter;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.conversationrecord.ConversationAttribution;
import com.qstory.backend.conversationrecord.util.ConversationAttributionParser;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.provider.ProviderReadiness;
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
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실시간 받아 적기 표 발급. 앱은 이 표로 wss /v1/stt/stream?ticket=...에 직접 붙어(웹은 Vercel 프록시가 WebSocket을 못
 * 들고 있어서) 말하는 동안 오디오를 보내고 중간 문장을 받는다. 거절되면(꺼짐·붐빔·상한) 앱은 배치 받아 적기
 * (POST /v1/companion-chat/transcriptions/base64)로 간다.
 */
@Tag(name = "SttStream", description = "Real-time speech-to-text over WebSocket (ticket + wss /v1/stt/stream)")
@RestController
public class SttStreamTicketController {

    private final AppProperties config;
    private final ObjectMapper objectMapper;
    private final SttStreamTicketService ticketService;
    private final StoryRegistryService storyRegistryService;
    private final CurrentUserResolver currentUserResolver;
    private final ConversationAttributionParser attributionParser;

    public SttStreamTicketController(
            AppProperties config, ObjectMapper objectMapper, SttStreamTicketService ticketService,
            StoryRegistryService storyRegistryService, CurrentUserResolver currentUserResolver,
            ConversationAttributionParser attributionParser) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.ticketService = ticketService;
        this.storyRegistryService = storyRegistryService;
        this.currentUserResolver = currentUserResolver;
        this.attributionParser = attributionParser;
    }

    @Operation(
            summary = "Issue a single-use real-time STT ticket",
            description = "Body {storyId, sceneId, sessionId?, childId?, tutorStudentId?, lessonId?} - the same context as "
                    + "POST /v1/companion-chat/transcriptions/base64. Returns {ok, ticket, expiresInMs, maxDurationMs, "
                    + "sampleRate, encoding}. Open wss://<api>/v1/stt/stream?ticket=<ticket> within expiresInMs (single use), "
                    + "send 16kHz mono s16le PCM as binary frames and {\"type\":\"end\"} when the child stops; the server "
                    + "answers {type:ready|partial|final|error}. Works without login (demo) - tied to sessionId and rate-limited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ticket"),
            @ApiResponse(responseCode = "400", description = "Missing storyId/sceneId",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "429", description = "Too many tickets for this session or overall",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "503", description = "Streaming disabled, busy, or STT not configured - use batch",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @PostMapping("/v1/stt/stream-tickets")
    public void issue(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonNode body = HttpBodyReader.readJsonBody(request, objectMapper);
        String storyId = requireText(body, "storyId");
        String sceneId = requireText(body, "sceneId");
        if (!ProviderReadiness.of(config).stt()) {
            throw ApiException.contractError(ErrorCode.STT_STREAM_UNAVAILABLE, "음성 인식 공급자가 아직 연결되지 않았어요.");
        }
        var caller = currentUserResolver.currentOrNull();
        ResolvedCompanionContext context = storyRegistryService.resolveCompanionChatContext(storyId, sceneId, null, caller);
        ConversationAttribution attribution = attributionParser.fromBody(body, ConversationInputMode.VOICE, caller);
        SttStreamTicket ticket = ticketService.issue(
                context.story().storyId(), context.sceneId(), context.sttKeywords(), attribution);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("ticket", ticket.id());
        result.put("expiresInMs", ticketService.properties().ticketTtlMs());
        result.put("maxDurationMs", ticketService.properties().maxDurationMs());
        result.put("sampleRate", 16_000);
        result.put("encoding", "LINEAR16");
        HttpJsonWriter.writeJson(response, objectMapper, 200, result);
    }

    private static String requireText(JsonNode body, String field) {
        String value = body != null && body.path(field).isTextual() ? body.get(field).asText().trim() : "";
        if (value.isEmpty()) {
            throw ApiException.contractError(ErrorCode.INVALID_REQUEST_CONTEXT, "받아 적기 요청 형식을 읽지 못했어요.");
        }
        return value;
    }
}

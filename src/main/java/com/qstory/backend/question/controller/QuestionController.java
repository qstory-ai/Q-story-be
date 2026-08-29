package com.qstory.backend.question.controller;
import com.qstory.backend.question.service.QuestionPipelineService;
import com.qstory.backend.question.util.QuestionContractValidator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.common.error.FailureBody;
import com.qstory.backend.story.service.StoryRegistryService;
import com.qstory.backend.story.service.StoryRegistryService.ResolvedQuestionContext;
import com.qstory.backend.common.util.HttpBodyReader;
import com.qstory.backend.common.util.HttpJsonWriter;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.identity.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 네 개의 질문/전사 엔드포인트에 대한 HTTP 배선으로, server.mjs의 핸들러를 그대로 따른다. */
@Tag(name = "Questions", description = "The child's spoken/typed question pipeline: transcribe, route to a story branch, and (for the JSON routes) that in one call")
@RestController
public class QuestionController {

    private static final Logger log = LoggerFactory.getLogger(QuestionController.class);

    private final AppProperties config;
    private final ObjectMapper objectMapper;
    private final QuestionContractValidator contractValidator;
    private final StoryRegistryService storyRegistryService;
    private final QuestionPipelineService pipeline;
    private final CurrentUserResolver currentUserResolver;

    public QuestionController(
            AppProperties config, ObjectMapper objectMapper, QuestionContractValidator contractValidator,
            StoryRegistryService storyRegistryService, QuestionPipelineService pipeline,
            CurrentUserResolver currentUserResolver) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.contractValidator = contractValidator;
        this.storyRegistryService = storyRegistryService;
        this.pipeline = pipeline;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(
            summary = "Transcribe a raw audio recording",
            description = "Body is the raw audio bytes (Content-Type is the audio mime type, e.g. audio/webm). "
                    + "Story/scene/anchor context comes from request headers, not the body. Does not route the "
                    + "question - see POST /v1/questions for transcribe+route in one call.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transcript"),
            @ApiResponse(responseCode = "400", description = "Missing/invalid context headers, or empty audio",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "413", description = "Audio exceeds qstory.max-audio-bytes",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "415", description = "Unsupported audio content type",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @PostMapping("/v1/transcriptions")
    public void transcribe(
            @Parameter(hidden = true) HttpServletRequest request, HttpServletResponse response) throws IOException {
        QuestionContractValidator.HeaderContext header = contractValidator.parseQuestionContext(request);
        byte[] audio = HttpBodyReader.readAudioBody(request, config.maxAudioBytes());
        ResolvedQuestionContext context = resolveContext(header, List.of(), false, currentUserResolver.currentOrNull());
        Map<String, Object> result = pipeline.transcribe(context, audio, deadline());
        HttpJsonWriter.writeJson(response, objectMapper, 200, result);
    }

    @Operation(
            summary = "Transcribe a base64-encoded audio recording",
            description = "JSON body {audioBase64, mimeType, storyId, sceneId, anchorId, questionRound} - the "
                    + "base64 alternative to POST /v1/transcriptions for clients that can't send raw "
                    + "multipart/binary bodies. Unlike /v1/transcriptions, all context lives in the body (a raw "
                    + "audio byte stream can't carry a JSON context alongside it, but this route already has a "
                    + "JSON body, so it carries context the same way POST /v1/questions/route does).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transcript"),
            @ApiResponse(responseCode = "400", description = "Malformed base64, empty audio, or invalid/missing context fields",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "413", description = "Decoded audio exceeds qstory.max-audio-bytes",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "{audioBase64, mimeType, storyId, sceneId, anchorId, questionRound}", required = true)
    @PostMapping("/v1/transcriptions/base64")
    public void transcribeBase64(
            @Parameter(hidden = true) HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpBodyReader.DecodedAudio decoded =
                HttpBodyReader.readBase64AudioBody(request, objectMapper, config.maxAudioBytes());
        QuestionContractValidator.HeaderContext header =
                contractValidator.parseQuestionContextFromBody(decoded.body(), decoded.mimeType());
        ResolvedQuestionContext context = resolveContext(header, List.of(), false, currentUserResolver.currentOrNull());
        Map<String, Object> result = pipeline.transcribe(context, decoded.audio(), deadline());
        HttpJsonWriter.writeJson(response, objectMapper, 200, result);
    }

    @Operation(
            summary = "Transcribe and route a spoken question in one call",
            description = "The primary child-facing endpoint: transcribes the raw audio, then routes it to a "
                    + "story branch via the LLM (see QuestionPipelineService). Required headers: "
                    + "x-qstory-story-id, x-qstory-scene-id, x-qstory-anchor-id, x-qstory-question-round.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transcript plus the routed narration"),
            @ApiResponse(responseCode = "400", description = "Missing/invalid context headers, or empty audio",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "413", description = "Audio exceeds qstory.max-audio-bytes",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "415", description = "Unsupported audio content type",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "502", description = "STT/LLM provider call failed",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @Parameter(in = ParameterIn.HEADER, name = "x-qstory-story-id", required = true, description = "e.g. \"HG\"")
    @Parameter(in = ParameterIn.HEADER, name = "x-qstory-scene-id", required = true)
    @Parameter(in = ParameterIn.HEADER, name = "x-qstory-anchor-id", required = true)
    @Parameter(in = ParameterIn.HEADER, name = "x-qstory-question-round", required = true, schema = @Schema(type = "integer"))
    @PostMapping("/v1/questions")
    public void question(
            @Parameter(hidden = true) HttpServletRequest request, HttpServletResponse response) throws IOException {
        QuestionContractValidator.HeaderContext header = contractValidator.parseQuestionContext(request);
        byte[] audio = HttpBodyReader.readAudioBody(request, config.maxAudioBytes());
        ResolvedQuestionContext context = resolveContext(header, List.of(), false, currentUserResolver.currentOrNull());
        Map<String, Object> result = pipeline.process(context, audio, deadline());
        HttpJsonWriter.writeJson(response, objectMapper, 200, result);
    }

    @Operation(
            summary = "Route an already-transcribed question",
            description = "JSON body carries storyId/sceneId/anchorId/questionRound/transcript directly (no "
                    + "audio, no context headers) plus optional priorActionFamilyIds/guaranteeAgencyChoice. Used "
                    + "when the transcript is already known (e.g. from a separate STT call or typed input).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Routed narration"),
            @ApiResponse(responseCode = "400", description = "Malformed body or invalid context",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "502", description = "LLM provider call failed",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "{storyId, sceneId, anchorId, questionRound, transcript, priorActionFamilyIds?, guaranteeAgencyChoice?}",
            required = true)
    @PostMapping("/v1/questions/route")
    public void questionRoute(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonNode body = HttpBodyReader.readJsonBody(request, objectMapper);
        QuestionContractValidator.TextQuestion parsed = contractValidator.parseTextQuestionRequest(body);
        ResolvedQuestionContext context = resolveContext(parsed, currentUserResolver.currentOrNull());
        Map<String, Object> result = pipeline.route(context, parsed.transcript(), deadline());
        HttpJsonWriter.writeJson(response, objectMapper, 200, result);
    }

    @Operation(
            summary = "Route a typed question, including its narration",
            description = "Same body shape as POST /v1/questions/route, but also synthesizes and returns the "
                    + "routed narration's audio (the typed-input equivalent of POST /v1/questions).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Routed narration plus synthesized audio"),
            @ApiResponse(responseCode = "400", description = "Malformed body or invalid context",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "502", description = "LLM/TTS provider call failed",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "{storyId, sceneId, anchorId, questionRound, transcript, priorActionFamilyIds?, guaranteeAgencyChoice?}",
            required = true)
    @PostMapping("/v1/text-questions")
    public void textQuestion(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonNode body = HttpBodyReader.readJsonBody(request, objectMapper);
        QuestionContractValidator.TextQuestion parsed = contractValidator.parseTextQuestionRequest(body);
        ResolvedQuestionContext context = resolveContext(parsed, currentUserResolver.currentOrNull());
        Map<String, Object> result = pipeline.processText(context, parsed.transcript(), deadline());
        HttpJsonWriter.writeJson(response, objectMapper, 200, result);
    }

    private ResolvedQuestionContext resolveContext(
            QuestionContractValidator.HeaderContext header, List<String> priorFamilyIds,
            boolean guaranteeAgencyChoice, com.qstory.backend.identity.security.CurrentUser callerOrNull) {
        return storyRegistryService.resolveStoryQuestionContext(
                header.storyId(), header.sceneId(), header.anchorId(), header.questionRound(),
                header.sourceMimeType(), priorFamilyIds, guaranteeAgencyChoice, callerOrNull);
    }

    private ResolvedQuestionContext resolveContext(
            QuestionContractValidator.TextQuestion parsed, com.qstory.backend.identity.security.CurrentUser callerOrNull) {
        return storyRegistryService.resolveStoryQuestionContext(
                parsed.storyId(), parsed.sceneId(), parsed.anchorId(), parsed.questionRound(),
                "text/plain", parsed.priorActionFamilyIds(), parsed.guaranteeAgencyChoice(), callerOrNull);
    }

    private RequestDeadline deadline() {
        return RequestDeadline.startingNow(config.requestTimeoutMs());
    }
}

package com.qstory.backend.question.controller;
import com.qstory.backend.question.service.QuestionPipelineService;
import com.qstory.backend.question.util.QuestionContractValidator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP wiring for the four question/transcription endpoints, mirroring server.mjs's handlers. */
@Tag(name = "Questions", description = "The child's spoken/typed question pipeline: transcribe, route to a story branch, and (for the JSON routes) that in one call")
@RestController
public class QuestionController {

    private static final Logger log = LoggerFactory.getLogger(QuestionController.class);
    private static final java.util.regex.Pattern BASE64_PATTERN = java.util.regex.Pattern.compile("^[A-Za-z0-9+/]*={0,2}$");

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
        ResolvedQuestionContext context = resolveContext(header, List.of(), false, currentUserResolver.current().orElse(null));
        Map<String, Object> result = pipeline.transcribe(context, audio, deadline());
        HttpJsonWriter.writeJson(response, objectMapper, 200, result);
    }

    @Operation(
            summary = "Transcribe a base64-encoded audio recording",
            description = "JSON body {audioBase64, mimeType} - the base64 alternative to POST "
                    + "/v1/transcriptions for clients that can't send raw multipart/binary bodies. Context headers "
                    + "same as /v1/transcriptions, except mimeType is read from the body instead of Content-Type.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transcript"),
            @ApiResponse(responseCode = "400", description = "Malformed base64, empty audio, or invalid context headers",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "413", description = "Decoded audio exceeds qstory.max-audio-bytes",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "{audioBase64, mimeType}", required = true)
    @PostMapping("/v1/transcriptions/base64")
    public void transcribeBase64(
            @Parameter(hidden = true) HttpServletRequest request, HttpServletResponse response) throws IOException {
        long maxBase64RequestBytes = ((config.maxAudioBytes() + 2) / 3) * 4 + 2_048;
        JsonNode body = HttpBodyReader.readJsonBody(request, objectMapper, maxBase64RequestBytes);
        DecodedAudio decoded = decodeBase64Audio(body);
        QuestionContractValidator.HeaderContext header =
                contractValidator.parseQuestionContextForMimeType(request, decoded.mimeType());
        ResolvedQuestionContext context = resolveContext(header, List.of(), false, currentUserResolver.current().orElse(null));
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
        ResolvedQuestionContext context = resolveContext(header, List.of(), false, currentUserResolver.current().orElse(null));
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
        ResolvedQuestionContext context = resolveContext(parsed, currentUserResolver.current().orElse(null));
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
        ResolvedQuestionContext context = resolveContext(parsed, currentUserResolver.current().orElse(null));
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

    private record DecodedAudio(byte[] audio, String mimeType) {}

    private DecodedAudio decodeBase64Audio(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw ApiException.contractError(ErrorCode.INVALID_BASE64_AUDIO_UPLOAD, "녹음 요청 형식을 읽지 못했어요.");
        }
        String audioBase64 = value.path("audioBase64").asText("").trim();
        String mimeType = value.path("mimeType").asText("").trim().toLowerCase();
        if (audioBase64.isEmpty() || mimeType.isEmpty()
                || audioBase64.length() % 4 == 1
                || !BASE64_PATTERN.matcher(audioBase64).matches()) {
            throw ApiException.contractError(ErrorCode.INVALID_BASE64_AUDIO_UPLOAD, "녹음 데이터가 비어 있거나 손상됐어요.");
        }
        byte[] audio;
        try {
            audio = Base64.getDecoder().decode(audioBase64);
        } catch (IllegalArgumentException malformed) {
            throw ApiException.contractError(ErrorCode.INVALID_BASE64_AUDIO_UPLOAD, "녹음 데이터가 비어 있거나 손상됐어요.");
        }
        if (audio.length == 0) {
            throw ApiException.contractError(ErrorCode.EMPTY_AUDIO, "녹음 데이터가 비어 있어요.");
        }
        if (audio.length > config.maxAudioBytes()) {
            throw ApiException.contractError(ErrorCode.AUDIO_TOO_LARGE, "The recording exceeds the upload limit", 413);
        }
        return new DecodedAudio(audio, mimeType);
    }
}

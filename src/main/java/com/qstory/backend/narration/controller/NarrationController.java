package com.qstory.backend.narration.controller;
import com.qstory.backend.narration.service.NarrationPipelineService;
import com.qstory.backend.narration.util.NarrationContractValidator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.FailureBody;
import com.qstory.backend.common.util.HttpBodyReader;
import com.qstory.backend.common.util.HttpJsonWriter;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.provider.openrouter.SynthesizedAudioStream;
import com.qstory.backend.story.service.StoryRegistryService;
import com.qstory.backend.story.service.StoryRegistryService.ResolvedNarrationContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** /v1/narrations 및 /v1/narrations/stream에 대한 HTTP 연결로, server.mjs의 핸들러를 그대로 반영한다. */
@Tag(name = "Narration", description = "On-demand TTS narration for a fixed (non-question) line of story text, spoken by an allowed cast voice")
@RestController
public class NarrationController {

    private static final Logger log = LoggerFactory.getLogger(NarrationController.class);

    private final AppProperties config;
    private final ObjectMapper objectMapper;
    private final NarrationContractValidator contractValidator;
    private final StoryRegistryService storyRegistryService;
    private final NarrationPipelineService pipeline;
    private final CurrentUserResolver currentUserResolver;

    public NarrationController(
            AppProperties config, ObjectMapper objectMapper, NarrationContractValidator contractValidator,
            StoryRegistryService storyRegistryService, NarrationPipelineService pipeline,
            CurrentUserResolver currentUserResolver) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.contractValidator = contractValidator;
        this.storyRegistryService = storyRegistryService;
        this.pipeline = pipeline;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(
            summary = "Synthesize narration audio for a fixed line of text (buffered)",
            description = "JSON body {storyId, anchorId, speakerId, text}. If anchorId is set (question-response "
                    + "flow), speakerId must be one of that anchor's allowedSpeakerIds. If anchorId is blank "
                    + "(general script narration), speakerId just needs to be a registered cast voice for the "
                    + "story. Returns the full audio in one response; see POST /v1/narrations/stream for a "
                    + "chunked PCM alternative.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Synthesized audio result"),
            @ApiResponse(responseCode = "400", description = "Malformed body",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "403", description = "Story not active, or speaker/voice not allowed for this story",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "502", description = "TTS provider call failed",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "{storyId, anchorId, speakerId, text}", required = true)
    @PostMapping("/v1/narrations")
    public void narrate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonNode body = HttpBodyReader.readJsonBody(request, objectMapper);
        NarrationContractValidator.NarrationRequest parsed = contractValidator.parse(body);
        ResolvedNarrationContext context = storyRegistryService.resolveNarrationContext(
                parsed.storyId(), parsed.anchorId(), parsed.speakerId(), currentUserResolver.current().orElse(null));
        Map<String, Object> result = pipeline.process(
                context, parsed.storyId(), parsed.speakerId(), parsed.text(),
                RequestDeadline.startingNow(config.requestTimeoutMs()));
        HttpJsonWriter.writeJson(response, objectMapper, 200, result);
    }

    @Operation(
            summary = "Synthesize narration audio for a fixed line of text (streamed)",
            description = "Same body/anchorId rule as POST /v1/narrations, but streams raw PCM as it's generated instead of "
                    + "buffering the whole clip. Success is a 200 with Content-Type audio/pcm and "
                    + "x-qstory-audio-* headers describing the stream (sample rate/channels/bit depth) - a "
                    + "failure is still a normal JSON error body (502/503), never a mid-stream cutoff.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "audio/pcm stream, sample rate/channels/bit depth in x-qstory-audio-* headers"),
            @ApiResponse(responseCode = "400", description = "Malformed body",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "403", description = "Story not active, or speaker/voice not allowed for this story",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "502", description = "TTS provider call failed and the failure is retryable",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "503", description = "TTS provider call failed and the failure is not retryable",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "{storyId, anchorId, speakerId, text}", required = true)
    @PostMapping("/v1/narrations/stream")
    public void narrateStream(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonNode body = HttpBodyReader.readJsonBody(request, objectMapper);
        NarrationContractValidator.NarrationRequest parsed = contractValidator.parse(body);
        ResolvedNarrationContext context = storyRegistryService.resolveNarrationContext(
                parsed.storyId(), parsed.anchorId(), parsed.speakerId(), currentUserResolver.current().orElse(null));
        NarrationPipelineService.StreamResult result = pipeline.processStream(
                context, parsed.storyId(), parsed.speakerId(), parsed.text(),
                RequestDeadline.startingNow(config.requestTimeoutMs()));

        if (!result.ok()) {
            Map<String, Object> failure = result.failure();
            Map<?, ?> failureBody = (Map<?, ?>) failure.get("failure");
            boolean retryable = Boolean.TRUE.equals(failureBody.get("retryable"));
            HttpJsonWriter.writeJson(response, objectMapper, retryable ? 502 : 503, failure);
            return;
        }
        streamPcm(response, result.audio());
    }

    private void streamPcm(HttpServletResponse response, SynthesizedAudioStream audio) throws IOException {
        response.setStatus(200);
        response.setContentType("audio/pcm");
        response.setHeader("Cache-Control", "no-store, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("x-qstory-audio-sample-rate", String.valueOf(audio.sampleRate()));
        response.setHeader("x-qstory-audio-channels", String.valueOf(audio.channels()));
        response.setHeader("x-qstory-audio-bit-depth", String.valueOf(audio.bitDepth()));
        if (audio.generationId() != null) {
            response.setHeader("x-qstory-generation-id", audio.generationId());
        }

        try (InputStream source = audio.stream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) != -1) {
                response.getOutputStream().write(buffer, 0, read);
                response.getOutputStream().flush();
            }
        } catch (IOException streamError) {
            log.warn("narration-stream.failed", streamError);
        }
    }
}

package com.qstory.backend.narration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.util.HttpBodyReader;
import com.qstory.backend.common.util.HttpJsonWriter;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.provider.openrouter.SynthesizedAudioStream;
import com.qstory.backend.story.StoryRegistryService;
import com.qstory.backend.story.StoryRegistryService.ResolvedNarrationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP wiring for /v1/narrations and /v1/narrations/stream, mirroring server.mjs's handlers. */
@RestController
public class NarrationController {

    private static final Logger log = LoggerFactory.getLogger(NarrationController.class);

    private final AppProperties config;
    private final ObjectMapper objectMapper;
    private final NarrationContractValidator contractValidator;
    private final StoryRegistryService storyRegistryService;
    private final NarrationPipelineService pipeline;

    public NarrationController(
            AppProperties config, ObjectMapper objectMapper, NarrationContractValidator contractValidator,
            StoryRegistryService storyRegistryService, NarrationPipelineService pipeline) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.contractValidator = contractValidator;
        this.storyRegistryService = storyRegistryService;
        this.pipeline = pipeline;
    }

    @PostMapping("/v1/narrations")
    public void narrate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonNode body = HttpBodyReader.readJsonBody(request, objectMapper);
        NarrationContractValidator.NarrationRequest parsed = contractValidator.parse(body);
        ResolvedNarrationContext context =
                storyRegistryService.resolveNarrationContext(parsed.storyId(), parsed.anchorId(), parsed.speakerId());
        Map<String, Object> result = pipeline.process(
                context, parsed.storyId(), parsed.speakerId(), parsed.text(),
                RequestDeadline.startingNow(config.requestTimeoutMs()));
        HttpJsonWriter.writeJson(response, objectMapper, 200, result);
    }

    @PostMapping("/v1/narrations/stream")
    public void narrateStream(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonNode body = HttpBodyReader.readJsonBody(request, objectMapper);
        NarrationContractValidator.NarrationRequest parsed = contractValidator.parse(body);
        ResolvedNarrationContext context =
                storyRegistryService.resolveNarrationContext(parsed.storyId(), parsed.anchorId(), parsed.speakerId());
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

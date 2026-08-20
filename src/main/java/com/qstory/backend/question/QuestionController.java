package com.qstory.backend.question;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.story.StoryRegistryService;
import com.qstory.backend.story.StoryRegistryService.ResolvedQuestionContext;
import com.qstory.backend.common.util.HttpBodyReader;
import com.qstory.backend.common.util.HttpJsonWriter;
import com.qstory.backend.common.util.RequestDeadline;
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
@RestController
public class QuestionController {

    private static final Logger log = LoggerFactory.getLogger(QuestionController.class);
    private static final java.util.regex.Pattern BASE64_PATTERN = java.util.regex.Pattern.compile("^[A-Za-z0-9+/]*={0,2}$");

    private final AppProperties config;
    private final ObjectMapper objectMapper;
    private final QuestionContractValidator contractValidator;
    private final StoryRegistryService storyRegistryService;
    private final QuestionPipelineService pipeline;

    public QuestionController(
            AppProperties config, ObjectMapper objectMapper, QuestionContractValidator contractValidator,
            StoryRegistryService storyRegistryService, QuestionPipelineService pipeline) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.contractValidator = contractValidator;
        this.storyRegistryService = storyRegistryService;
        this.pipeline = pipeline;
    }

    @PostMapping("/v1/transcriptions")
    public void transcribe(HttpServletRequest request, HttpServletResponse response) throws IOException {
        QuestionContractValidator.HeaderContext header = contractValidator.parseQuestionContext(request);
        byte[] audio = HttpBodyReader.readAudioBody(request, config.maxAudioBytes());
        ResolvedQuestionContext context = resolveContext(header, List.of(), false);
        Map<String, Object> result = pipeline.transcribe(context, audio, deadline());
        HttpJsonWriter.writeJson(response, objectMapper, 200, result);
    }

    @PostMapping("/v1/transcriptions/base64")
    public void transcribeBase64(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long maxBase64RequestBytes = ((config.maxAudioBytes() + 2) / 3) * 4 + 2_048;
        JsonNode body = HttpBodyReader.readJsonBody(request, objectMapper, maxBase64RequestBytes);
        DecodedAudio decoded = decodeBase64Audio(body);
        QuestionContractValidator.HeaderContext header =
                contractValidator.parseQuestionContextForMimeType(request, decoded.mimeType());
        ResolvedQuestionContext context = resolveContext(header, List.of(), false);
        Map<String, Object> result = pipeline.transcribe(context, decoded.audio(), deadline());
        HttpJsonWriter.writeJson(response, objectMapper, 200, result);
    }

    @PostMapping("/v1/questions")
    public void question(HttpServletRequest request, HttpServletResponse response) throws IOException {
        QuestionContractValidator.HeaderContext header = contractValidator.parseQuestionContext(request);
        byte[] audio = HttpBodyReader.readAudioBody(request, config.maxAudioBytes());
        ResolvedQuestionContext context = resolveContext(header, List.of(), false);
        Map<String, Object> result = pipeline.process(context, audio, deadline());
        HttpJsonWriter.writeJson(response, objectMapper, 200, result);
    }

    @PostMapping("/v1/questions/route")
    public void questionRoute(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonNode body = HttpBodyReader.readJsonBody(request, objectMapper);
        QuestionContractValidator.TextQuestion parsed = contractValidator.parseTextQuestionRequest(body);
        ResolvedQuestionContext context = resolveContext(parsed);
        Map<String, Object> result = pipeline.route(context, parsed.transcript(), deadline());
        HttpJsonWriter.writeJson(response, objectMapper, 200, result);
    }

    @PostMapping("/v1/text-questions")
    public void textQuestion(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonNode body = HttpBodyReader.readJsonBody(request, objectMapper);
        QuestionContractValidator.TextQuestion parsed = contractValidator.parseTextQuestionRequest(body);
        ResolvedQuestionContext context = resolveContext(parsed);
        Map<String, Object> result = pipeline.processText(context, parsed.transcript(), deadline());
        HttpJsonWriter.writeJson(response, objectMapper, 200, result);
    }

    private ResolvedQuestionContext resolveContext(QuestionContractValidator.HeaderContext header, List<String> priorFamilyIds, boolean guaranteeAgencyChoice) {
        return storyRegistryService.resolveStoryQuestionContext(
                header.storyId(), header.sceneId(), header.anchorId(), header.questionRound(),
                header.sourceMimeType(), priorFamilyIds, guaranteeAgencyChoice);
    }

    private ResolvedQuestionContext resolveContext(QuestionContractValidator.TextQuestion parsed) {
        return storyRegistryService.resolveStoryQuestionContext(
                parsed.storyId(), parsed.sceneId(), parsed.anchorId(), parsed.questionRound(),
                "text/plain", parsed.priorActionFamilyIds(), parsed.guaranteeAgencyChoice());
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

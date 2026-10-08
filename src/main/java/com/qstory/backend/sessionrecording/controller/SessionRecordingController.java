package com.qstory.backend.sessionrecording.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.HttpBodyReader;
import com.qstory.backend.common.util.HttpJsonWriter;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.recordingconsent.service.RecordingConsentService;
import com.qstory.backend.sessionrecording.service.SessionRecordingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * UT 화면 녹화 조각 수집(072). 로그인 없이 받고, 토큰이 같이 오면 그 계정을 남긴다. 화면 녹화 동의(073)가 있을 때만
 * 받는다 - 조각마다 확인하므로 철회하면 다음 조각부터 403이다.
 *
 * <p>Spring의 @RequestBody 대신 직접 읽는다 - 조각 하나가 1MB 가까이 되므로 상한(2MB)을 여기서 걸고 넘으면 413으로
 * 답한다. Tomcat의 max-http-form-post-size는 폼 요청에만 걸리고 JSON 본문에는 걸리지 않아 따로 늘릴 설정은 없다.
 */
@Tag(name = "Session recordings", description = "rrweb screen recording chunks for UT replay")
@RestController
public class SessionRecordingController {

    /** data 1,000,000자 + JSON 이스케이프·나머지 필드 여유. */
    static final long MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final SessionRecordingService service;
    private final CurrentUserResolver currentUserResolver;
    private final RecordingConsentService consentService;

    public SessionRecordingController(
            ObjectMapper objectMapper, SessionRecordingService service, CurrentUserResolver currentUserResolver,
            RecordingConsentService consentService) {
        this.objectMapper = objectMapper;
        this.service = service;
        this.currentUserResolver = currentUserResolver;
        this.consentService = consentService;
    }

    @Operation(
            summary = "Upload one rrweb recording chunk",
            description = "Anonymous allowed (betaSessionId required); an optional bearer token links the user. "
                    + "Idempotent per (betaSessionId, seq). data up to 1,000,000 characters and 50MB per beta session, "
                    + "else 413 PAYLOAD_TOO_LARGE (the client should stop recording). Needs a granted screen recording "
                    + "consent - of this beta session, or of the signed-in account (the later decision wins) - else 403 "
                    + "RECORDING_NOT_CONSENTED, checked on every chunk so a withdrawal stops uploads. Responds 202 {ok, stored}.")
    @PostMapping("/v1/session-recordings/chunks")
    public void upload(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SessionRecordingService.ParsedChunk chunk = SessionRecordingService.parse(readJson(request), Instant.now());
        UUID userId = currentUserResolver.current().map(CurrentUser::userId).orElse(null);
        consentService.requireUploadAllowed(chunk.betaSessionId(), userId);
        boolean stored = service.store(chunk, userId);
        HttpJsonWriter.writeJson(response, objectMapper, 202, Map.of("ok", true, "stored", stored));
    }

    private JsonNode readJson(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_PAYLOAD_BYTES) {
            throw ApiException.contractError(ErrorCode.PAYLOAD_TOO_LARGE, "녹화 조각이 너무 커요.", 413);
        }
        byte[] body = HttpBodyReader.readAllBytes(
                request.getInputStream(), MAX_PAYLOAD_BYTES, ErrorCode.PAYLOAD_TOO_LARGE, "녹화 조각이 너무 커요.");
        try {
            return objectMapper.readTree(body);
        } catch (IOException malformed) {
            throw ApiException.contractError(ErrorCode.INVALID_JSON, "요청 형식이 올바르지 않아요.");
        }
    }
}

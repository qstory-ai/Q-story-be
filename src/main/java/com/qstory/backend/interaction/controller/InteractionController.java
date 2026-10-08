package com.qstory.backend.interaction.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.util.HttpBodyReader;
import com.qstory.backend.common.util.HttpJsonWriter;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.interaction.service.InteractionService;
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

/** UT 화면 상호작용 묶음 수집(072). 로그인 없이 받고, 토큰이 같이 오면 그 계정을 남긴다. */
@Tag(name = "Interactions", description = "Batched screen interaction events (view/leave/tap/scroll/hesitation) for UT analysis")
@RestController
public class InteractionController {

    /** 200개 x 이벤트당 1KB 남짓(metadata 2,000자 상한 포함)을 넉넉히 덮는다. */
    static final long MAX_PAYLOAD_BYTES = 512 * 1024;

    private final ObjectMapper objectMapper;
    private final InteractionService service;
    private final CurrentUserResolver currentUserResolver;

    public InteractionController(ObjectMapper objectMapper, InteractionService service, CurrentUserResolver currentUserResolver) {
        this.objectMapper = objectMapper;
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(
            summary = "Record a batch of interaction events",
            description = "Anonymous allowed (betaSessionId required); an optional bearer token links the user. "
                    + "At most 200 events; unknown kind is 400; events whose occurredAt is more than 7 days old or "
                    + "5 minutes in the future are dropped. Responds 202 {ok, accepted}.")
    @PostMapping("/v1/interactions")
    public void record(HttpServletRequest request, HttpServletResponse response) throws IOException {
        InteractionService.ParsedBatch batch = InteractionService.parse(readJson(request), Instant.now());
        UUID userId = currentUserResolver.current().map(CurrentUser::userId).orElse(null);
        int accepted = service.record(batch, userId);
        HttpJsonWriter.writeJson(response, objectMapper, 202, Map.of("ok", true, "accepted", accepted));
    }

    private JsonNode readJson(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_PAYLOAD_BYTES) {
            throw ApiException.contractError(ErrorCode.PAYLOAD_TOO_LARGE, "요청이 너무 커요.", 413);
        }
        byte[] body = HttpBodyReader.readAllBytes(
                request.getInputStream(), MAX_PAYLOAD_BYTES, ErrorCode.PAYLOAD_TOO_LARGE, "요청이 너무 커요.");
        try {
            return objectMapper.readTree(body);
        } catch (IOException malformed) {
            throw ApiException.contractError(ErrorCode.INVALID_JSON, "요청 형식이 올바르지 않아요.");
        }
    }
}

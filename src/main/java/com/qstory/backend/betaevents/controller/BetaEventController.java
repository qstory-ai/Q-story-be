package com.qstory.backend.betaevents.controller;
import com.qstory.backend.betaevents.util.BetaEventValidator;
import com.qstory.backend.betaevents.service.BetaEventService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.error.FailureBody;
import com.qstory.backend.common.util.HttpJsonWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** supabase/functions/beta-events/index.ts를 Java로 이식한 것. */
@Tag(name = "Beta events", description = "Pseudonymous, cookie-free funnel telemetry for the beta - session lifecycle and question-routing events")
@RestController
public class BetaEventController {

    private static final long MAX_PAYLOAD_BYTES = 8_192;

    private final ObjectMapper objectMapper;
    private final BetaEventValidator validator;
    private final BetaEventService service;

    public BetaEventController(ObjectMapper objectMapper, BetaEventValidator validator, BetaEventService service) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.service = service;
    }

    @Operation(
            summary = "Record one funnel/telemetry event",
            description = "Upserts the event's session (client-generated id, idempotent) and appends an "
                    + "append-only event row (client-generated id, so retried uploads dedupe safely). Body is a "
                    + "single JSON object; exact shape depends on event_name (see BetaEventValidator) - session "
                    + "fields (entry_source, traffic_type, UTM params, ...) and event fields (event_name, "
                    + "occurred_at, metadata, ...) are both accepted on every call, session fields are ignored "
                    + "after the session's first event. Capped at 8KB.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted",
                    content = @Content(schema = @Schema(example = "{\"ok\":true}"))),
            @ApiResponse(responseCode = "400", description = "Malformed JSON or a field failed validation",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "413", description = "Body exceeds 8KB",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "429", description = "Session is posting events too fast",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @PostMapping("/v1/beta-events")
    public void record(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonNode body = readJson(request);
        BetaEventValidator.ParsedEvent event = validator.parse(body);
        service.record(event);
        HttpJsonWriter.writeJson(response, objectMapper, 202, Map.of("ok", true));
    }

    private JsonNode readJson(HttpServletRequest request) throws IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_PAYLOAD_BYTES) {
            throw ApiException.contractError(ErrorCode.PAYLOAD_TOO_LARGE, "요청이 너무 커요.", 413);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        long total = 0;
        int read;
        while ((read = request.getInputStream().read(chunk)) != -1) {
            total += read;
            if (total > MAX_PAYLOAD_BYTES) {
                throw ApiException.contractError(ErrorCode.PAYLOAD_TOO_LARGE, "요청이 너무 커요.", 413);
            }
            buffer.write(chunk, 0, read);
        }
        try {
            return objectMapper.readTree(buffer.toByteArray());
        } catch (IOException malformed) {
            throw ApiException.contractError(ErrorCode.INVALID_JSON, "요청 형식이 올바르지 않아요.");
        }
    }
}

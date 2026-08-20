package com.qstory.backend.betaevents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.EdgeErrorCode;
import com.qstory.backend.common.error.EdgeException;
import com.qstory.backend.common.util.HttpJsonWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Java port of supabase/functions/beta-events/index.ts. */
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
            throw new EdgeException(EdgeErrorCode.PAYLOAD_TOO_LARGE);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        long total = 0;
        int read;
        while ((read = request.getInputStream().read(chunk)) != -1) {
            total += read;
            if (total > MAX_PAYLOAD_BYTES) {
                throw new EdgeException(EdgeErrorCode.PAYLOAD_TOO_LARGE);
            }
            buffer.write(chunk, 0, read);
        }
        try {
            return objectMapper.readTree(buffer.toByteArray());
        } catch (IOException malformed) {
            throw new EdgeException(EdgeErrorCode.INVALID_JSON);
        }
    }
}

package com.qstory.backend.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads request bodies with the same manual, size-checked loop as server.mjs's
 * readAudioBody()/readJsonBody()/decodeBase64Audio() - Spring's automatic body binding does not
 * enforce these limits with matching error codes, so bodies are read as raw bytes here instead.
 */
public final class HttpBodyReader {

    private HttpBodyReader() {}

    public static byte[] readAudioBody(HttpServletRequest request, long maxAudioBytes) throws IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxAudioBytes) {
            throw ApiException.contractError(ErrorCode.AUDIO_TOO_LARGE, "The recording exceeds the upload limit");
        }
        byte[] body = readAllBytes(
                request.getInputStream(), maxAudioBytes, ErrorCode.AUDIO_TOO_LARGE,
                "The recording exceeds the upload limit");
        if (body.length == 0) {
            throw ApiException.contractError(ErrorCode.EMPTY_AUDIO, "The recording is empty");
        }
        return body;
    }

    public static JsonNode readJsonBody(HttpServletRequest request, ObjectMapper objectMapper) throws IOException {
        return readJsonBody(request, objectMapper, 4_096);
    }

    public static JsonNode readJsonBody(HttpServletRequest request, ObjectMapper objectMapper, long maxBytes)
            throws IOException {
        String contentType = request.getContentType() == null ? "" : request.getContentType();
        String base = contentType.split(";", 2)[0].trim().toLowerCase();
        if (!"application/json".equals(base)) {
            throw ApiException.contractError(
                    ErrorCode.UNSUPPORTED_CONTENT_TYPE, "Narration request must use application/json");
        }
        byte[] body = readAllBytes(
                request.getInputStream(), maxBytes, ErrorCode.NARRATION_REQUEST_TOO_LARGE,
                "Narration request exceeds the size limit");
        try {
            return objectMapper.readTree(body);
        } catch (IOException parseError) {
            throw ApiException.contractError(ErrorCode.INVALID_JSON, "Narration request is not valid JSON");
        }
    }

    private static byte[] readAllBytes(InputStream input, long maxBytes, ErrorCode tooLargeCode, String tooLargeDetail)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long receivedBytes = 0;
        int read;
        while ((read = input.read(chunk)) != -1) {
            receivedBytes += read;
            if (receivedBytes > maxBytes) {
                throw ApiException.contractError(tooLargeCode, tooLargeDetail);
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}

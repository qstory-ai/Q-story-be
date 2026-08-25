package com.qstory.backend.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

/**
 * server.mjs의 readAudioBody()/readJsonBody()/decodeBase64Audio()와 동일하게, 크기를 직접
 * 검사하는 수동 루프로 요청 바디를 읽는다 - Spring의 자동 바디 바인딩은 이런 제한을 그에 맞는
 * 에러 코드와 함께 강제해주지 않으므로, 여기서는 대신 바디를 raw 바이트로 직접 읽는다.
 */
public final class HttpBodyReader {

    private static final java.util.regex.Pattern BASE64_PATTERN = java.util.regex.Pattern.compile("^[A-Za-z0-9+/]*={0,2}$");

    /** body는 audioBase64/mimeType을 이미 소비한 것과 같은, 파싱된 요청 JSON 전체다 -
     * 호출부가 storyId/sceneId 같은 나머지 컨텍스트 필드를 body에서 마저 읽을 수 있게 한다
     * (요청 스트림은 한 번만 읽을 수 있어서 따로 다시 읽을 수 없다). */
    public record DecodedAudio(byte[] audio, String mimeType, JsonNode body) {}

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

    /** JSON 바디 {audioBase64, mimeType}를 읽고 디코딩한다 - /v1/transcriptions/base64와
     * /v1/companion-chat/transcriptions/base64가 공유하는 로직. */
    public static DecodedAudio readBase64AudioBody(
            HttpServletRequest request, ObjectMapper objectMapper, long maxAudioBytes) throws IOException {
        long maxBase64RequestBytes = ((maxAudioBytes + 2) / 3) * 4 + 2_048;
        JsonNode body = readJsonBody(request, objectMapper, maxBase64RequestBytes);
        if (body == null || !body.isObject()) {
            throw ApiException.contractError(ErrorCode.INVALID_BASE64_AUDIO_UPLOAD, "녹음 요청 형식을 읽지 못했어요.");
        }
        String audioBase64 = body.path("audioBase64").asText("").trim();
        String mimeType = body.path("mimeType").asText("").trim().toLowerCase();
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
        if (audio.length > maxAudioBytes) {
            throw ApiException.contractError(ErrorCode.AUDIO_TOO_LARGE, "The recording exceeds the upload limit", 413);
        }
        return new DecodedAudio(audio, mimeType, body);
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

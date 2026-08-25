package com.qstory.backend.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qstory.backend.config.AppProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * service role key로 인증하는, Supabase Storage REST API용 얇은(thin) 클라이언트다.
 * 이 백엔드는 자체 Postgres 스키마를 직접 소유하고 있지만, voice-research와
 * shadow-generation이 만들어내는 오디오/이미지 blob에 대해서는 여전히 Supabase Storage를 쓴다.
 */
@Component
public class SupabaseStorageClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String serviceRoleKey;

    public SupabaseStorageClient(HttpClient httpClient, ObjectMapper objectMapper, AppProperties config) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUrl = config.supabase().url();
        this.serviceRoleKey = config.supabase().serviceRoleKey();
    }

    public boolean upload(String bucket, String objectName, byte[] content, String contentType) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/storage/v1/object/" + bucket + "/" + objectName))
                    .timeout(Duration.ofSeconds(30))
                    .header("authorization", "Bearer " + serviceRoleKey)
                    .header("content-type", contentType)
                    .header("x-upsert", "false")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() / 100 == 2;
        } catch (java.io.IOException | InterruptedException error) {
            return false;
        }
    }

    /**
     * shadowAssetsBucket처럼 비공개(public=false)인 bucket에서 시간 제한이 있는 다운로드 URL을
     * 받는다 - upload()가 쓰는 storyAudioBucket과 달리 이 bucket은 영구 public URL이 없다.
     * 실패하면 null을 반환한다(호출자는 이미지/오디오 없이 대본만이라도 저장할지 판단할 수 있다).
     */
    public String createSignedUrl(String bucket, String objectName, int expiresInSeconds) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("expiresIn", expiresInSeconds);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/storage/v1/object/sign/" + bucket + "/" + objectName))
                    .timeout(Duration.ofSeconds(30))
                    .header("authorization", "Bearer " + serviceRoleKey)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toString().getBytes(StandardCharsets.UTF_8)))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            JsonNode payload = objectMapper.readTree(response.body());
            String signedPath = payload.path("signedURL").asText(null);
            return signedPath == null ? null : baseUrl + "/storage/v1" + signedPath;
        } catch (Exception error) {
            return null;
        }
    }

    public boolean delete(String bucket, String objectName) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/storage/v1/object/" + bucket + "/" + objectName))
                    .timeout(Duration.ofSeconds(30))
                    .header("authorization", "Bearer " + serviceRoleKey)
                    .DELETE()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() / 100 == 2 || response.statusCode() == 404;
        } catch (java.io.IOException | InterruptedException error) {
            return false;
        }
    }
}

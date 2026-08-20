package com.qstory.backend.common.util;

import com.qstory.backend.config.AppProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Thin client for the Supabase Storage REST API, authenticated with the service role key.
 * This backend owns its own Postgres schema directly (see StoryContentSeeder), but still uses
 * Supabase Storage for the audio/image blobs voice-research and shadow-generation produce.
 */
@Component
public class SupabaseStorageClient {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String serviceRoleKey;

    public SupabaseStorageClient(HttpClient httpClient, AppProperties config) {
        this.httpClient = httpClient;
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

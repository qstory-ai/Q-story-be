package com.qstory.backend.push.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.config.FcmProperties;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FcmClientTest {

    private static final String TOKEN = "fcm-token-abc:APA91b_xyz";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = mock(HttpClient.class);
    private final FcmClient client = new FcmClient(http, objectMapper, "qstory-test", () -> "access-123");
    private final PushMessage message =
            new PushMessage(UUID.fromString("11111111-2222-3333-4444-555555555555"), "tutor-report",
                    "리포트가 도착했어요", "하린이의 수업 기록", "/parent/reports/1");

    @SuppressWarnings("unchecked")
    private void respond(int status, String body) throws Exception {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        doReturn(response).when(http).send(any(HttpRequest.class), any());
    }

    private HttpRequest sentRequest() throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captor.capture(), any());
        return captor.getValue();
    }

    @Test
    void buildsV1RequestWithNotificationDataAndAndroidChannel() throws Exception {
        respond(200, "{\"name\":\"projects/qstory-test/messages/1\"}");

        FcmSendResult result = client.send(TOKEN, message);

        assertEquals(FcmSendResult.Outcome.SENT, result.outcome());
        HttpRequest request = sentRequest();
        assertEquals("https://fcm.googleapis.com/v1/projects/qstory-test/messages:send", request.uri().toString());
        assertEquals("POST", request.method());
        assertEquals("Bearer access-123", request.headers().firstValue("Authorization").orElseThrow());
        JsonNode msg = objectMapper.readTree(bodyOf(request)).path("message");
        assertEquals(TOKEN, msg.path("token").asText());
        assertEquals("리포트가 도착했어요", msg.path("notification").path("title").asText());
        assertEquals("하린이의 수업 기록", msg.path("notification").path("body").asText());
        assertEquals("/parent/reports/1", msg.path("data").path("href").asText());
        assertEquals("tutor-report", msg.path("data").path("kind").asText());
        assertEquals("11111111-2222-3333-4444-555555555555", msg.path("data").path("notificationId").asText());
        assertEquals("high", msg.path("android").path("priority").asText());
        assertEquals("qstory_default", msg.path("android").path("notification").path("channel_id").asText());
    }

    @Test
    void emptyBodyAndHrefAreLeftOutBecauseDataValuesMustBeStrings() {
        JsonNode msg = client.body(TOKEN, new PushMessage(UUID.randomUUID(), "invite-accepted", "초대를 수락했어요", null, null))
                .path("message");
        assertFalse(msg.path("notification").has("body"));
        assertFalse(msg.path("data").has("href"));
        assertTrue(msg.path("data").path("notificationId").isTextual());
    }

    @Test
    void unregisteredTokenIsReportedInvalid() throws Exception {
        respond(404, """
                {"error":{"code":404,"message":"Requested entity was not found.","status":"NOT_FOUND",
                 "details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}
                """);

        FcmSendResult result = client.send(TOKEN, message);

        assertEquals(FcmSendResult.Outcome.TOKEN_INVALID, result.outcome());
        assertEquals("UNREGISTERED", result.reason());
        assertEquals(404, result.status());
    }

    @Test
    void malformedTokenIsReportedInvalid() throws Exception {
        respond(400, """
                {"error":{"code":400,"message":"The registration token is not a valid FCM registration token",
                 "status":"INVALID_ARGUMENT",
                 "details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"INVALID_ARGUMENT"},
                  {"@type":"type.googleapis.com/google.rpc.BadRequest","fieldViolations":[{"field":"message.token"}]}]}}
                """);

        assertEquals(FcmSendResult.Outcome.TOKEN_INVALID, client.send(TOKEN, message).outcome());
    }

    @Test
    void invalidPayloadKeepsTheToken() throws Exception {
        respond(400, """
                {"error":{"code":400,"message":"Invalid value at 'message.android.priority'","status":"INVALID_ARGUMENT",
                 "details":[{"@type":"type.googleapis.com/google.rpc.BadRequest",
                  "fieldViolations":[{"field":"message.android.priority"}]}]}}
                """);

        FcmSendResult result = client.send(TOKEN, message);

        assertEquals(FcmSendResult.Outcome.FAILED, result.outcome());
        assertEquals("INVALID_ARGUMENT", result.reason());
    }

    @Test
    void serverErrorAndAuthErrorKeepTheToken() throws Exception {
        respond(503, "{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\"}}");
        assertEquals(FcmSendResult.Outcome.FAILED, client.send(TOKEN, message).outcome());

        respond(401, "not json");
        FcmSendResult unauthorized = client.send(TOKEN, message);
        assertEquals(FcmSendResult.Outcome.FAILED, unauthorized.outcome());
        assertEquals("http-401", unauthorized.reason());
    }

    @Test
    void accessTokenFailureIsAFailureWithoutCallingFcm() {
        FcmClient broken = new FcmClient(http, objectMapper, "qstory-test", () -> {
            throw new java.io.IOException("boom");
        });

        FcmSendResult result = broken.send(TOKEN, message);

        assertEquals(FcmSendResult.Outcome.FAILED, result.outcome());
        assertEquals("auth", result.reason());
        verifyNoInteractions(http);
    }

    @Test
    void unconfiguredClientIsDisabledAndNeverCallsFcm() {
        for (FcmProperties properties : new FcmProperties[] {
                null, new FcmProperties(null, null), new FcmProperties("  ", "qstory"),
                new FcmProperties("not json or base64 !!", null),
                new FcmProperties("{\"type\":\"service_account\"}", null),
                new FcmProperties("{\"type\":\"service_account\",\"project_id\":\"p\",\"client_email\":\"a@p.iam\","
                        + "\"private_key\":\"not a key\"}", null)}) {
            FcmClient disabled = new FcmClient(http, objectMapper, properties);
            assertFalse(disabled.enabled());
            assertEquals(FcmSendResult.Outcome.FAILED, disabled.send(TOKEN, message).outcome());
        }
        verifyNoInteractions(http);
    }

    @Test
    void realServiceAccountKeyEnablesTheClientAndProjectIdCanBeOverridden() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String json = objectMapper.writeValueAsString(java.util.Map.of(
                "type", "service_account", "project_id", "from-json", "private_key_id", "kid",
                "private_key", pem, "client_email", "fcm@from-json.iam.gserviceaccount.com", "client_id", "1",
                "token_uri", "https://oauth2.googleapis.com/token"));

        FcmClient.Setup fromJson = FcmClient.Setup.from(new FcmProperties(json, null), objectMapper);
        assertEquals("from-json", fromJson.projectId());
        assertTrue(fromJson.tokens() != null);

        String base64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertEquals("override", FcmClient.Setup.from(new FcmProperties(base64, " override "), objectMapper).projectId());
        assertTrue(new FcmClient(http, objectMapper, new FcmProperties(base64, null)).enabled());
    }

    @Test
    void serviceAccountJsonMayBeRawOrBase64() {
        String json = "{\"type\":\"service_account\",\"project_id\":\"p\"}";
        assertEquals(json, FcmClient.Setup.decodeServiceAccount("  " + json + "\n"));
        assertEquals(json, FcmClient.Setup.decodeServiceAccount(
                Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))));
        assertEquals(json, FcmClient.Setup.decodeServiceAccount(
                Base64.getMimeEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))));
        assertNull(FcmClient.Setup.decodeServiceAccount("bm90IGpzb24="));
    }

    @Test
    void tokenHashIsShortAndStable() {
        String hash = FcmClient.tokenHash(TOKEN);
        assertEquals(8, hash.length());
        assertEquals(hash, FcmClient.tokenHash(TOKEN));
    }

    private static String bodyOf(HttpRequest request) throws Exception {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompletableFuture<Void> done = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                out.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });
        done.get();
        return out.toString(StandardCharsets.UTF_8);
    }
}

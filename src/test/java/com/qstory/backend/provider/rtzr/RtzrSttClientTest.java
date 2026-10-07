package com.qstory.backend.provider.rtzr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.error.ProviderException;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.provider.rtzr.util.RtzrSttClient;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class RtzrSttClientTest {

    private static final String TOKEN_OK = "{\"access_token\":\"t\",\"expire_at\":4102444800}";

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    private RtzrSttClient client(HttpResponse<byte[]> first, HttpResponse<byte[]>... rest) throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(), any())).thenReturn((HttpResponse) first, (HttpResponse[]) rest);
        AppProperties config = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(config.providers().rtzr().clientId()).thenReturn("id");
        when(config.providers().rtzr().clientSecret()).thenReturn("secret");
        return new RtzrSttClient(http, new ObjectMapper(), config);
    }

    private void transcribe(RtzrSttClient client) {
        client.transcribe(new byte[] {1}, "wav", "audio/wav", List.of(), RequestDeadline.startingNow(5_000));
    }

    @Test
    @SuppressWarnings("unchecked")
    void billingErrorOnSubmitIsSttUnavailable() throws Exception {
        RtzrSttClient client = client(
                response(200, TOKEN_OK), response(400, "{\"code\":\"H0001\",\"msg\":\"Card registration is required\"}"));
        ApiException error = assertThrows(ApiException.class, () -> transcribe(client));
        assertEquals(ErrorCode.STT_UNAVAILABLE, error.code());
        assertEquals(503, error.statusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void authRejectedIsSttUnavailable() throws Exception {
        RtzrSttClient client = client(response(401, "{\"code\":\"A0001\"}"));
        ApiException error = assertThrows(ApiException.class, () -> transcribe(client));
        assertEquals(ErrorCode.STT_UNAVAILABLE, error.code());
    }

    @Test
    @SuppressWarnings("unchecked")
    void forbiddenOnSubmitIsSttUnavailable() throws Exception {
        RtzrSttClient client = client(response(200, TOKEN_OK), response(403, "{}"));
        assertEquals(ErrorCode.STT_UNAVAILABLE, assertThrows(ApiException.class, () -> transcribe(client)).code());
    }

    @Test
    @SuppressWarnings("unchecked")
    void serverErrorKeepsExistingRetryableProviderFailure() throws Exception {
        RtzrSttClient client = client(response(200, TOKEN_OK), response(503, "{}"));
        ProviderException error = assertThrows(ProviderException.class, () -> transcribe(client));
        assertTrue(error.retryable());
    }

    @Test
    @SuppressWarnings("unchecked")
    void authServerErrorKeepsExistingRetryableProviderFailure() throws Exception {
        RtzrSttClient client = client(response(500, "{}"));
        assertThrows(ProviderException.class, () -> transcribe(client));
    }
}

package com.qstory.backend.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Vercel 프록시가 서명해 넘긴 클라이언트 주소만 믿는다 - 맞는 서명·만료·위조·비밀 없음. */
class ClientIpResolverTest {

    private static final String SECRET = "test-secret-0123456789";
    private static final long NOW = 1_760_000_000L;
    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);

    private static MockHttpServletRequest signed(String ip, long ts, String signature) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/narrations");
        request.setRemoteAddr("76.76.21.1");
        request.addHeader(ClientIpResolver.CLIENT_IP_HEADER, ip);
        request.addHeader(ClientIpResolver.TIMESTAMP_HEADER, Long.toString(ts));
        request.addHeader(ClientIpResolver.SIGNATURE_HEADER, signature);
        return request;
    }

    @Test
    void signatureMatchesTheProxyFormat() {
        // fe/src/shared/api/proxy-client-ip.test.ts가 같은 입력으로 같은 값을 확인한다.
        assertThat(ClientIpResolver.sign("secret", "203.0.113.7", 1_760_000_000L))
                .isEqualTo("1d66427ccf6f146914958555b7d4930b78f9c85e18b9545ce886c53c4505cd39");
    }

    @Test
    void validSignatureUsesTheForwardedIp() {
        ClientIpResolver resolver = new ClientIpResolver(SECRET, "", clock);
        String sig = ClientIpResolver.sign(SECRET, "203.0.113.7", NOW - 5);
        assertThat(resolver.resolve(signed("203.0.113.7", NOW - 5, sig))).isEqualTo("203.0.113.7");
    }

    @Test
    void ipv6IsAccepted() {
        ClientIpResolver resolver = new ClientIpResolver(SECRET, "", clock);
        String ip = "2001:db8::1";
        assertThat(resolver.resolve(signed(ip, NOW, ClientIpResolver.sign(SECRET, ip, NOW)))).isEqualTo(ip);
    }

    @Test
    void expiredSignatureFallsBackToRemoteAddress() {
        ClientIpResolver resolver = new ClientIpResolver(SECRET, "", clock);
        long old = NOW - ClientIpResolver.MAX_SKEW_SECONDS - 1;
        assertThat(resolver.resolve(signed("203.0.113.7", old, ClientIpResolver.sign(SECRET, "203.0.113.7", old))))
                .isEqualTo("76.76.21.1");
        long future = NOW + ClientIpResolver.MAX_SKEW_SECONDS + 1;
        assertThat(resolver.resolve(signed("203.0.113.7", future, ClientIpResolver.sign(SECRET, "203.0.113.7", future))))
                .isEqualTo("76.76.21.1");
    }

    @Test
    void forgedSignatureFallsBackToRemoteAddress() {
        ClientIpResolver resolver = new ClientIpResolver(SECRET, "", clock);
        // 다른 비밀로 서명했거나, 서명한 주소를 바꿔 보냈다.
        assertThat(resolver.resolve(signed("203.0.113.7", NOW, ClientIpResolver.sign("other", "203.0.113.7", NOW))))
                .isEqualTo("76.76.21.1");
        assertThat(resolver.resolve(signed("203.0.113.8", NOW, ClientIpResolver.sign(SECRET, "203.0.113.7", NOW))))
                .isEqualTo("76.76.21.1");
        assertThat(resolver.resolve(signed("203.0.113.7", NOW, "nothex"))).isEqualTo("76.76.21.1");
    }

    @Test
    void malformedIpIsRejectedEvenWithAValidSignature() {
        ClientIpResolver resolver = new ClientIpResolver(SECRET, "", clock);
        String ip = "evil value=1";
        assertThat(resolver.resolve(signed(ip, NOW, ClientIpResolver.sign(SECRET, ip, NOW)))).isEqualTo("76.76.21.1");
    }

    @Test
    void withoutASecretTheHeaderIsIgnored() {
        ClientIpResolver resolver = new ClientIpResolver("", "", clock);
        assertThat(resolver.resolve(signed("203.0.113.7", NOW, ClientIpResolver.sign("x", "203.0.113.7", NOW))))
                .isEqualTo("76.76.21.1");
    }

    @Test
    void unsignedRequestsUseTheConfiguredEdgeHeader() {
        ClientIpResolver resolver = new ClientIpResolver(SECRET, "X-Real-IP", clock);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/auth/login");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Real-IP", "198.51.100.4");
        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.4");

        MockHttpServletRequest garbage = new MockHttpServletRequest("POST", "/v1/auth/login");
        garbage.setRemoteAddr("10.0.0.1");
        garbage.addHeader("X-Real-IP", "not an ip");
        assertThat(resolver.resolve(garbage)).isEqualTo("10.0.0.1");
    }

    @Test
    void validSignatureWinsOverTheEdgeHeader() {
        ClientIpResolver resolver = new ClientIpResolver(SECRET, "X-Real-IP", clock);
        MockHttpServletRequest request = signed("203.0.113.7", NOW, ClientIpResolver.sign(SECRET, "203.0.113.7", NOW));
        request.addHeader("X-Real-IP", "76.76.21.1");
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }
}

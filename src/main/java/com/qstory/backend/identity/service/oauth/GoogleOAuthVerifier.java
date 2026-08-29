package com.qstory.backend.identity.service.oauth;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.config.AppProperties;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 프론트가 Google Identity Services(GIS)로 받은 id_token을 그대로 서버로 보내면, 여기서
 * Google의 tokeninfo 엔드포인트로 서명/만료를 검증한다 - JWKS를 직접 받아 서명 검증을 구현하는
 * 대신 Google이 공식 문서로 지원하는 간단한 방법을 쓴다(이 서비스 규모에서 tokeninfo의 요청
 * 제한은 문제되지 않는다).
 *
 * <p>aud를 우리 client-id와 비교하는 게 검증의 핵심이다 - 이 비교를 생략하면, 완전히 다른(전혀
 * 무관한) 앱에 로그인해서 발급받은 id_token을 그대로 재사용해 우리 서비스에 로그인/가입하는 것도
 * 가능해진다(OIDC 토큰 재사용/혼동 공격).
 */
@Component
public class GoogleOAuthVerifier {

    private static final String TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo";

    private final OAuthHttpClient httpClient;
    private final AppProperties config;

    public GoogleOAuthVerifier(OAuthHttpClient httpClient, AppProperties config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    public record GoogleIdentity(String subject, String email, String displayName) {}

    public GoogleIdentity verify(String idToken) {
        AppProperties.Google google = config.providers().oauth().google();
        if (!google.configured()) {
            throw ApiException.contractError(
                    ErrorCode.OAUTH_PROVIDER_NOT_CONFIGURED, "구글 로그인이 아직 설정되지 않았어요.", 503);
        }

        Map<String, Object> payload = httpClient.get(
                TOKENINFO_URL + "?id_token={token}", "구글 로그인 확인에 실패했어요.", idToken);

        if (payload == null || !google.clientId().equals(payload.get("aud"))) {
            throw ApiException.contractError(ErrorCode.OAUTH_TOKEN_INVALID, "구글 로그인 확인에 실패했어요.", 401);
        }

        Object subject = payload.get("sub");
        if (subject == null) {
            throw ApiException.contractError(ErrorCode.OAUTH_TOKEN_INVALID, "구글 로그인 확인에 실패했어요.", 401);
        }
        return new GoogleIdentity(
                String.valueOf(subject),
                (String) payload.get("email"),
                (String) payload.get("name"));
    }
}

package com.qstory.backend.push.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.error.FailureBody;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.push.PushPlatform;
import com.qstory.backend.push.service.PushTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 앱 푸시(FCM) 기기 토큰 등록·해제(075). 앱이 로그인 뒤·토큰이 바뀔 때 등록하고, 로그아웃 때 해제한다. 해제는 DELETE
 * 본문이 프록시에서 빠지는 경우가 있어 POST /remove로 둔다. 역할 제한은 없다 - 어느 역할이든 알림을 받는다.
 */
@Tag(name = "Push tokens", description = "FCM device tokens for app push notifications")
@RestController
@RequestMapping("/v1/me/push-tokens")
public class PushTokenController {

    /** push_device_token.token varchar(512). FCM 토큰은 영숫자와 : _ - 로 이뤄진다 - 공백·제어 문자만 막는다. */
    static final int MAX_TOKEN_LENGTH = 512;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\x21-\\x7E]+");

    private final PushTokenService service;
    private final CurrentUserResolver currentUserResolver;

    public PushTokenController(PushTokenService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Register the caller's device token",
            description = "Any signed-in role. Body {token, platform: ANDROID|IOS|WEB}. Upsert: a token that belonged to "
                    + "another account (same device, different login) moves to the caller; a disabled token is re-enabled.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Registered"),
            @ApiResponse(responseCode = "400", description = "token or platform missing/invalid",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(@RequestBody(required = false) JsonNode body) {
        UUID userId = currentUserResolver.require().userId();
        String token = token(body);
        PushPlatform platform = platform(body);
        service.register(userId, token, platform);
    }

    @Operation(summary = "Remove the caller's device token",
            description = "Any signed-in role. Body {token}. Only removes it if it belongs to the caller; 204 either way.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Removed (or not the caller's)"),
            @ApiResponse(responseCode = "400", description = "token missing/invalid",
                    content = @Content(schema = @Schema(implementation = FailureBody.class))),
            @ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(schema = @Schema(implementation = FailureBody.class)))
    })
    @PostMapping("/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@RequestBody(required = false) JsonNode body) {
        UUID userId = currentUserResolver.require().userId();
        service.remove(userId, token(body));
    }

    private static String token(JsonNode body) {
        JsonNode node = body == null ? null : body.get("token");
        String token = node != null && node.isTextual() ? node.asText().trim() : null;
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH
                || !TOKEN_PATTERN.matcher(token).matches()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "token이 비었거나 형식이 맞지 않아요.");
        }
        return token;
    }

    private static PushPlatform platform(JsonNode body) {
        JsonNode node = body.get("platform");
        if (node != null && node.isTextual()) {
            try {
                return PushPlatform.valueOf(node.asText().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                // 아래에서 400.
            }
        }
        throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "platform은 ANDROID, IOS, WEB 중 하나여야 해요.");
    }
}

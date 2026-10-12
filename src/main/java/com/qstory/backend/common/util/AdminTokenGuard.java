package com.qstory.backend.common.util;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.ratelimit.RateLimitRule;
import com.qstory.backend.ratelimit.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * X-Admin-Token 헤더 검증 - AuthController(STAFF 계정 발급)와 StoryImportController(콘텐츠
 * 임포트)가 공유한다. 보안 비교가 두 곳에서 따로 수정되다 어긋나지 않도록 한 곳에 둔다.
 */
@Component
public class AdminTokenGuard {

    private final AppProperties config;
    private final RateLimitService rateLimitService;

    public AdminTokenGuard(AppProperties config, RateLimitService rateLimitService) {
        this.config = config;
        this.rateLimitService = rateLimitService;
    }

    public void require(HttpServletRequest request) {
        if (!config.admin().storyImportTokenConfigured()) {
            throw ApiException.contractError(ErrorCode.INTERNAL_ERROR, "이 기능은 아직 준비되지 않았어요.", 500);
        }
        // 토큰 대입 막기(보안 점검 M2) - 한 클라이언트가 1시간에 여러 번 틀리면 비교하지 않고 429.
        rateLimitService.requireNotBlocked(request, RateLimitRule.ADMIN_TOKEN_FAILURE);
        String provided = request.getHeader("X-Admin-Token");
        if (provided == null || !DigestUtil.constantTimeEquals(config.admin().storyImportToken(), provided)) {
            rateLimitService.recordFailure(request, RateLimitRule.ADMIN_TOKEN_FAILURE);
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "이 작업을 수행할 권한이 없어요.", 403);
        }
    }
}

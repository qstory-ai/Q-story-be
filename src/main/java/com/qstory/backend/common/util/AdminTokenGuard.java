package com.qstory.backend.common.util;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * X-Admin-Token 헤더 검증 - AuthController(STAFF 계정 발급)와 StoryImportController(콘텐츠
 * 임포트)가 공유한다. 보안 비교가 두 곳에서 따로 수정되다 어긋나지 않도록 한 곳에 둔다.
 */
@Component
public class AdminTokenGuard {

    private final AppProperties config;

    public AdminTokenGuard(AppProperties config) {
        this.config = config;
    }

    public void require(HttpServletRequest request) {
        if (!config.admin().storyImportTokenConfigured()) {
            throw ApiException.contractError(ErrorCode.INTERNAL_ERROR, "이 기능은 아직 준비되지 않았어요.", 500);
        }
        String provided = request.getHeader("X-Admin-Token");
        if (provided == null || !DigestUtil.constantTimeEquals(config.admin().storyImportToken(), provided)) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "이 작업을 수행할 권한이 없어요.", 403);
        }
    }
}

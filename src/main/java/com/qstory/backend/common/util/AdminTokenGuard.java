package com.qstory.backend.common.util;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * X-Admin-Token 헤더 검증 - AuthController(STAFF 계정 발급)와 StoryImportController(콘텐츠
 * 임포트)가 각자 손으로 다시 작성해 갖고 있던 requireAdminToken()을 하나로 모았다. 두 사본이
 * 따로 수정되다 어긋나는 게 진짜 위험이었다.
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
        if (!DigestUtil.matchesAdminToken(provided, config.admin().storyImportToken())) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "이 작업을 수행할 권한이 없어요.", 403);
        }
    }
}

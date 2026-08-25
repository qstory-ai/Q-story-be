package com.qstory.backend.identity.security;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.Role;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * StoryImportController.requireAdminToken()이 이미 사용하고 있던 인라인 패턴을 재사용 가능하게
 * 일반화한 것 - 컨트롤러가 보호된 메서드 맨 위에서 이것을 명시적으로 호출하며, 애너테이션이나
 * AOP는 쓰지 않는다.
 */
@Component
public class CurrentUserResolver {

    public Optional<CurrentUser> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof CurrentUser user)) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    public CurrentUser require() {
        return current().orElseThrow(
                () -> ApiException.contractError(ErrorCode.UNAUTHENTICATED, "로그인이 필요해요.", 401));
    }

    public CurrentUser requireRole(Role... allowed) {
        CurrentUser user = require();
        if (Arrays.stream(allowed).noneMatch(role -> role == user.role())) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "이 작업을 수행할 권한이 없어요.", 403);
        }
        return user;
    }
}

package com.qstory.backend.org;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUser;
import java.util.UUID;

/**
 * "이 사람이 이 기관의 원장인가" 한 가지 검사. 컨트롤러가 requireRole(DIRECTOR)로 역할을 거른 뒤, 서비스가 요청한
 * 기관 id가 토큰의 orgId(원장에게만 있다)와 같은지 여기서 본다. 기관 서비스 여섯 곳이 같은 검사를 각자 적고 문구까지
 * 달랐던 것을 한 곳으로 모았다.
 */
public final class OrganizationAccess {

    public static final String FORBIDDEN_DETAIL = "이 기관에 접근할 권한이 없어요.";

    private OrganizationAccess() {}

    public static void requireDirectorOf(CurrentUser caller, UUID organizationId) {
        if (caller == null || caller.role() != Role.DIRECTOR || caller.orgId() == null
                || !caller.orgId().equals(organizationId)) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, FORBIDDEN_DETAIL, 403);
        }
    }
}

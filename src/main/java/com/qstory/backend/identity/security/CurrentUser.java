package com.qstory.backend.identity.security;

import com.qstory.backend.identity.Role;
import java.util.UUID;

/** 검증된 JWT의 claim에서 확정된 프로세스 내(in-process) 아이덴티티 - 이 필드들 때문에 DB를 다시 조회하는 일은 없다. */
public record CurrentUser(UUID userId, Role role, UUID orgId, UUID classId) {}

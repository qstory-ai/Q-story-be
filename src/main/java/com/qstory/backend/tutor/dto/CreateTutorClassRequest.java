package com.qstory.backend.tutor.dto;

import java.util.UUID;

/**
 * 선생님이 만드는 반. organizationId는 선택 - 있으면 그 기관(선생님이 소속된 기관이어야 한다) 안의
 * 반이 되어 기관 관리자도 반 목록에서 본다. 없으면 선생님 개인 반.
 */
public record CreateTutorClassRequest(String name, UUID organizationId) {}

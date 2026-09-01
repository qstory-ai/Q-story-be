package com.qstory.backend.org.tutor.dto;

/**
 * 초대 미리보기 응답 - 수락 전에 선생님이 "어느 기관의 초대인지"만 확인할 수 있게 한다.
 * 시크릿을 노출하지 않으므로 인증 없이 접근 가능.
 */
public record OrganizationTutorInvitePreviewResponse(String organizationName) {}

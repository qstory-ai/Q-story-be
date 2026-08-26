package com.qstory.backend.identity.dto;

/** childName은 PARENT가 아닌 계정이 보내도 AuthService.updateProfile()에서 조용히 무시된다. */
public record UpdateProfileRequest(String displayName, String childName) {}

package com.qstory.backend.identity.dto;

public record AuthResponse(String token, UserSummary user) {}

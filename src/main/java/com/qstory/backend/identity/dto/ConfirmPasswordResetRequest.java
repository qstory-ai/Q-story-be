package com.qstory.backend.identity.dto;

public record ConfirmPasswordResetRequest(String token, String newPassword) {}

package com.qstory.backend.identity.dto;

public record ChangePasswordRequest(String currentPassword, String newPassword) {}

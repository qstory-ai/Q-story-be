package com.qstory.backend.org.dto;

/** Exactly one of classCode (durable, reusable) / inviteToken (single-use) must be present. */
public record JoinClassRequest(
        String classCode, String inviteToken, String email, String password, String displayName) {}

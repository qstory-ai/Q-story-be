package com.qstory.backend.org.dto;

/** classCode(영구적, 재사용 가능)와 inviteToken(1회용) 중 정확히 하나만 존재해야 한다. */
public record JoinClassRequest(
        String classCode, String inviteToken, String email, String password, String displayName) {}

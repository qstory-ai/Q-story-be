package com.qstory.backend.org.dto;

import java.time.Instant;

/** 담임 초대 미리보기 - 로그인 없이 어느 기관·반의 담임 초대인지 보여 준다. 지금 담임이 없으면 currentHomeroomName은 null. */
public record ClassHomeroomInvitePreviewResponse(
        String organizationName, String className, Instant expiresAt, String currentHomeroomName) {}

package com.qstory.backend.tutor.dto;

/** method는 "SMS" 또는 "LINK" - SMS면 phoneNumber가 필요하다. */
public record CreateTutorInviteRequest(String method, String phoneNumber) {}

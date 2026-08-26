package com.qstory.backend.identity.dto;

/** reasonDetail은 선택 입력이다. */
public record DeleteAccountRequest(String reasonCategory, String reasonDetail) {}

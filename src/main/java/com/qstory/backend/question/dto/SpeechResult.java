package com.qstory.backend.question.dto;

public record SpeechResult(String status, String transcript, String locale, String normalizedMimeType) {

    public static SpeechResult of(String transcript, String locale, String normalizedMimeType) {
        return new SpeechResult("speech", transcript, locale, normalizedMimeType);
    }
}

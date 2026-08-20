package com.qstory.backend.question.dto;

import java.util.Base64;

public record AudioPayload(String mimeType, String dataBase64) {

    public static AudioPayload of(String mimeType, byte[] audio) {
        return new AudioPayload(mimeType, Base64.getEncoder().encodeToString(audio));
    }
}

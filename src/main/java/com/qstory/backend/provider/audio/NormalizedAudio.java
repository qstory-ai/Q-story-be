package com.qstory.backend.provider.audio;

public record NormalizedAudio(byte[] audio, String extension, String mimeType, boolean converted) {}

package com.qstory.backend.provider.openrouter;

import java.io.InputStream;

public record SynthesizedAudioStream(
        InputStream stream, String mimeType, int sampleRate, int channels, int bitDepth, String generationId) {}

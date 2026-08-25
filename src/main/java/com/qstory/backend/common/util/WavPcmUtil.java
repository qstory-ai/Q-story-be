package com.qstory.backend.common.util;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** raw 16비트 PCM 데이터를 최소 44바이트짜리 WAV 헤더로 감싼다. openrouter.mjs의 wrapPcmAsWav()와 동일하게 동작한다. */
public final class WavPcmUtil {

    private WavPcmUtil() {}

    public static byte[] wrapPcmAsWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        short blockAlign = (short) (channels * bitsPerSample / 8);
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes());
        header.putInt(36 + pcm.length);
        header.put("WAVE".getBytes());
        header.put("fmt ".getBytes());
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) channels);
        header.putInt(sampleRate);
        header.putInt(byteRate);
        header.putShort(blockAlign);
        header.putShort((short) bitsPerSample);
        header.put("data".getBytes());
        header.putInt(pcm.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        out.writeBytes(header.array());
        out.writeBytes(pcm);
        return out.toByteArray();
    }
}

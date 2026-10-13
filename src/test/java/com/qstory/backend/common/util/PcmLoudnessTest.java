package com.qstory.backend.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** 실시간 TTS 음량을 미리 녹음한 고정 음성(-17 LUFS)에 맞추는 PcmLoudness. */
class PcmLoudnessTest {

    private static final int RATE = 24_000;

    /** 997 Hz 사인(BS.1770 기준 신호) - amplitude는 0~1, 앞에 leadSilence초 무음. */
    private static byte[] sine(double amplitude, double seconds, double leadSilence) {
        int lead = (int) (leadSilence * RATE);
        int total = lead + (int) (seconds * RATE);
        byte[] pcm = new byte[total * 2];
        for (int index = lead; index < total; index++) {
            int sample = (int) Math.round(Math.sin(2 * Math.PI * 997 * index / RATE) * amplitude * 32767);
            pcm[index * 2] = (byte) sample;
            pcm[index * 2 + 1] = (byte) (sample >> 8);
        }
        return pcm;
    }

    private static double peak(byte[] pcm) {
        int max = 0;
        for (int index = 0; index + 1 < pcm.length; index += 2) {
            max = Math.max(max, Math.abs((short) ((pcm[index] & 0xff) | (pcm[index + 1] << 8))));
        }
        return max / 32768.0;
    }

    /** Gemini 조각처럼 들쭉날쭉한(홀수 바이트 포함) 크기로 내주는 원본. */
    private static InputStream chunked(byte[] pcm, int chunk) {
        return new ByteArrayInputStream(pcm) {
            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                return super.read(buffer, offset, Math.min(length, chunk));
            }
        };
    }

    private static byte[] drain(InputStream stream, int readSize) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[readSize];
        int read;
        while ((read = stream.read(buffer, 0, buffer.length)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    @Test
    void measuresTheReferenceSineLikeBs1770() {
        // 0 dBFS 997 Hz 사인 = -3.01 LUFS, -20 dB면 -23.01 LUFS.
        assertThat(PcmLoudness.integratedLufs(sine(0.1, 3, 0), RATE)).isCloseTo(-23.0, within(0.2));
        assertThat(PcmLoudness.integratedLufs(sine(1.0, 3, 0), RATE)).isCloseTo(-3.0, within(0.2));
    }

    @Test
    void leadingSilenceDoesNotLowerTheMeasurement() {
        assertThat(PcmLoudness.integratedLufs(sine(0.1, 3, 1.0), RATE)).isCloseTo(-23.0, within(0.3));
    }

    @Test
    void silenceIsLeftAlone() {
        byte[] silence = new byte[RATE * 2];
        assertThat(PcmLoudness.integratedLufs(silence, RATE)).isInfinite();
        assertThat(PcmLoudness.normalize(silence, RATE, -17)).isSameAs(silence);
    }

    @Test
    void normalizesAQuietClipToTheTarget() {
        byte[] quiet = sine(0.1, 3, 0.25); // -23 LUFS
        byte[] leveled = PcmLoudness.normalize(quiet, RATE, -17);
        assertThat(leveled).hasSameSizeAs(quiet);
        assertThat(PcmLoudness.integratedLufs(leveled, RATE)).isCloseTo(-17.0, within(0.2));
    }

    @Test
    void gainIsBoundedAndPeaksStayBelowMinusOneDbfs() {
        byte[] veryQuiet = sine(0.01, 2, 0); // -43 LUFS: +26 dB가 필요하지만 +9 dB까지만
        assertThat(PcmLoudness.integratedLufs(PcmLoudness.normalize(veryQuiet, RATE, -17), RATE))
                .isCloseTo(-43.0 + PcmLoudness.MAX_GAIN_DB, within(0.3));

        byte[] loud = PcmLoudness.normalize(sine(0.6, 2, 0), RATE, -2); // 키우면 1.0을 넘을 소리
        assertThat(peak(loud)).isLessThanOrEqualTo(Math.pow(10, -1.0 / 20) + 1e-4);
    }

    @Test
    void streamingLevelsToTheTargetAndKeepsEveryByte() throws IOException {
        byte[] quiet = sine(0.08, 4, 0.3); // 약 -25 LUFS, 앞 0.3초 무음
        try (InputStream leveled = PcmLoudness.leveling(chunked(quiet, 1_153), RATE, -17)) {
            byte[] out = drain(leveled, 777);
            assertThat(out).hasSameSizeAs(quiet);
            assertThat(PcmLoudness.integratedLufs(out, RATE)).isCloseTo(-17.0, within(0.5));
        }
    }

    @Test
    void streamingHoldsBackOnlyUntilEnoughSpeechToDecide() throws IOException {
        byte[] speech = sine(0.1, 3, 0.3);
        try (InputStream leveled = PcmLoudness.leveling(chunked(speech, 960), RATE, -17)) {
            // 첫 read는 무음 0.3초 + 말소리 약 0.6초를 한꺼번에 내준다(그 사이 이득이 정해진다).
            byte[] first = new byte[RATE * 2 * 3];
            int firstRead = leveled.read(first, 0, first.length);
            double firstSeconds = firstRead / 2.0 / RATE;
            assertThat(firstSeconds).isBetween(0.85, 1.0);
        }
    }

    @Test
    void streamingGainChangesSlowlyWhenTheVoiceGetsLouder() throws IOException {
        // 앞 1.5초는 작게, 뒤 1.5초는 6 dB 크게 - 이득이 계단처럼 바뀌지 않고 천천히 따라간다.
        byte[] soft = sine(0.05, 1.5, 0);
        byte[] loud = sine(0.1, 1.5, 0);
        byte[] both = new byte[soft.length + loud.length];
        System.arraycopy(soft, 0, both, 0, soft.length);
        System.arraycopy(loud, 0, both, soft.length, loud.length);
        try (InputStream leveled = PcmLoudness.leveling(chunked(both, 960), RATE, -17)) {
            byte[] out = drain(leveled, 4096);
            int windowBytes = RATE / 10 * 2; // 100ms
            double previous = -1;
            for (int start = 0; start + windowBytes <= out.length; start += windowBytes) {
                byte[] window = java.util.Arrays.copyOfRange(out, start, start + windowBytes);
                byte[] source = java.util.Arrays.copyOfRange(both, start, start + windowBytes);
                double gain = peak(window) / peak(source);
                if (previous > 0) {
                    double changeDb = Math.abs(20 * Math.log10(gain / previous));
                    // 100ms마다 SLEW_DB_PER_SECOND/10 dB 이하(반올림 여유).
                    assertThat(changeDb).isLessThanOrEqualTo(PcmLoudness.SLEW_DB_PER_SECOND / 10 + 0.05);
                }
                previous = gain;
            }
        }
    }

    @Test
    void streamingPassesSilenceThrough() throws IOException {
        byte[] silence = new byte[RATE]; // 0.5초
        try (InputStream leveled = PcmLoudness.leveling(chunked(silence, 100), RATE, -17)) {
            assertThat(drain(leveled, 333)).isEqualTo(silence);
        }
        try (InputStream empty = PcmLoudness.leveling(new ByteArrayInputStream(new byte[0]), RATE, -17)) {
            assertThat(empty.read(new byte[10], 0, 10)).isEqualTo(-1);
        }
    }
}

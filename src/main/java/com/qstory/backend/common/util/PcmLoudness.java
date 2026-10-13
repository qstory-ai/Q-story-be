package com.qstory.backend.common.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 16비트 mono PCM(little-endian)의 음량(ITU-R BS.1770 K-가중·게이트 적분 LUFS)을 재고 목표 음량으로 맞춘다.
 *
 * <p>미리 녹음한 고정 음성은 전부 약 -17 LUFS로 맞춰 올라가 있는데(Q-32), 실시간 Gemini TTS는 호출마다 음량이 제각각이다
 * (2026-10 실측, 같은 모델·목소리·입력 형식으로 6개: -14.6 ~ -22.5 LUFS). 녹음한 대사 바로 뒤에 실시간 대사가 오면 목소리가
 * 갑자기 커지거나 작아져 "튄다"로 들린다. 그래서 실시간 음성도 같은 목표로 맞춘다.
 *
 * <ul>
 *   <li>{@link #normalize}: 전체를 가진 경우(버퍼링 응답) - 적분 음량을 재 한 번의 이득을 준다.</li>
 *   <li>{@link #leveling}: 스트리밍 - 첫 말소리 일부(약 0.6초)를 모아 이득을 정한 뒤 내보내고, 그 뒤로는 지금까지 받은 소리의
 *       최근 약 2초 음량을 따라 이득을 천천히(초당 {@value #SLEW_DB_PER_SECOND} dB 이하) 고친다 - 문장 안에서 음량이 출렁이지 않게.
 *       앞으로 올 소리를 모르는 채로 맞추므로 버퍼링만큼 정확하지는 않다(실측 6개: 원래 -14.6 ~ -22.5 → -16.1 ~ -19.1 LUFS).</li>
 * </ul>
 * 이득은 ±{@value #MAX_GAIN_DB} dB로 묶고, 키운 소리가 -1 dBFS를 넘지 않게 부드럽게 눌러 깨지지 않게 한다.
 */
public final class PcmLoudness {

    /** 미리 녹음한 고정 음성의 음량(Q-32 내레이터 중앙값) - 실시간 음성도 여기에 맞춘다. */
    public static final double DEFAULT_TARGET_LUFS = -17.0;

    static final double MAX_GAIN_DB = 9.0;
    static final double SLEW_DB_PER_SECOND = 4.0;
    /** 스트리밍에서 이득을 따라갈 때 볼 최근 소리(초) - 문장 앞이 커도 뒤로 갈수록 그 문장의 음량을 따라가게. */
    static final double RUNNING_WINDOW_SECONDS = 2.0;
    /**
     * 첫 말소리는 문장 평균보다 크게 나온다(실측 6개 평균 약 +2 LU) - 첫 이득을 정할 때 이만큼 덜 크다고 본다.
     * 반만 반영해 첫마디가 지나치게 커지지 않게 한다.
     */
    static final double ONSET_BIAS_DB = 1.0;
    /** 스트리밍에서 이득을 정하기 전에 모을 말소리(초) - 앞의 무음은 세지 않는다. */
    static final double PRIME_SPEECH_SECONDS = 0.6;
    /** 말소리가 늦게 시작해도 이만큼(초) 모이면 정한다. */
    static final double PRIME_MAX_SECONDS = 1.2;
    /** 이 크기(dBFS)를 넘는 샘플부터 말소리로 본다 - 앞 무음을 건너뛰는 데만 쓴다. */
    private static final double ONSET_DBFS = -45.0;
    private static final double CEILING = Math.pow(10, -1.0 / 20); // -1 dBFS
    private static final double KNEE = 0.7;
    private static final double ABSOLUTE_GATE_LUFS = -70.0;
    private static final double RELATIVE_GATE_LU = -10.0;

    private PcmLoudness() {}

    /** 적분 음량(LUFS). 재 볼 만한 소리가 없으면(무음·빈 입력) {@link Double#NEGATIVE_INFINITY}. */
    public static double integratedLufs(byte[] pcm, int sampleRate) {
        Meter meter = new Meter(sampleRate);
        short[] samples = toSamples(pcm, pcm.length);
        for (short sample : samples) {
            meter.add(sample / 32768.0);
        }
        return meter.integrated();
    }

    /** 전체 클립을 목표 음량으로 - 이득 한 번 + 피크 보호. 잴 수 없으면 그대로 돌려준다. */
    public static byte[] normalize(byte[] pcm, int sampleRate, double targetLufs) {
        double measured = integratedLufs(pcm, sampleRate);
        if (Double.isInfinite(measured) || Double.isNaN(measured)) {
            return pcm;
        }
        double gain = dbToLinear(gainDb(measured, targetLufs));
        short[] samples = toSamples(pcm, pcm.length);
        byte[] out = new byte[samples.length * 2];
        for (int index = 0; index < samples.length; index++) {
            putSample(out, index * 2, limit(samples[index] / 32768.0 * gain));
        }
        return out;
    }

    /** 스트리밍 PCM을 목표 음량으로 맞추며 내보낸다(클래스 설명 참고). close()는 원본도 닫는다. */
    public static InputStream leveling(InputStream pcm, int sampleRate, double targetLufs) {
        return new LevelingStream(pcm, sampleRate, targetLufs);
    }

    static double gainDb(double measuredLufs, double targetLufs) {
        if (Double.isInfinite(measuredLufs) || Double.isNaN(measuredLufs)) {
            return 0;
        }
        return Math.max(-MAX_GAIN_DB, Math.min(MAX_GAIN_DB, targetLufs - measuredLufs));
    }

    /** -1 dBFS를 넘지 않게 - 0.7 아래는 그대로, 위는 tanh로 부드럽게 눌러 CEILING에 다가가게 한다. */
    static double limit(double value) {
        double magnitude = Math.abs(value);
        if (magnitude <= KNEE) {
            return value;
        }
        double range = CEILING - KNEE;
        return Math.signum(value) * (KNEE + range * Math.tanh((magnitude - KNEE) / range));
    }

    private static double dbToLinear(double db) {
        return Math.pow(10, db / 20);
    }

    private static short[] toSamples(byte[] pcm, int length) {
        short[] samples = new short[length / 2];
        for (int index = 0; index < samples.length; index++) {
            samples[index] = (short) ((pcm[index * 2] & 0xff) | (pcm[index * 2 + 1] << 8));
        }
        return samples;
    }

    private static void putSample(byte[] out, int offset, double value) {
        int sample = (int) Math.round(value * 32767.0);
        sample = Math.max(-32768, Math.min(32767, sample));
        out[offset] = (byte) sample;
        out[offset + 1] = (byte) (sample >> 8);
    }

    /** BS.1770 K-가중 필터(셸빙 + 고역 통과) - 계수는 libebur128처럼 샘플레이트에서 계산한다. */
    static final class KWeighting {
        private final double[] b1 = new double[3];
        private final double[] a1 = new double[3];
        private final double[] b2 = new double[3];
        private final double[] a2 = new double[3];
        private double x11, x12, y11, y12, x21, x22, y21, y22;

        KWeighting(int sampleRate) {
            double f0 = 1681.974450955533;
            double gain = 3.999843853973347;
            double q = 0.7071752369554196;
            double k = Math.tan(Math.PI * f0 / sampleRate);
            double vh = Math.pow(10.0, gain / 20.0);
            double vb = Math.pow(vh, 0.4996667741545416);
            double a0 = 1.0 + k / q + k * k;
            b1[0] = (vh + vb * k / q + k * k) / a0;
            b1[1] = 2.0 * (k * k - vh) / a0;
            b1[2] = (vh - vb * k / q + k * k) / a0;
            a1[1] = 2.0 * (k * k - 1.0) / a0;
            a1[2] = (1.0 - k / q + k * k) / a0;

            f0 = 38.13547087602444;
            q = 0.5003270373238773;
            k = Math.tan(Math.PI * f0 / sampleRate);
            double a0hp = 1.0 + k / q + k * k;
            b2[0] = 1.0;
            b2[1] = -2.0;
            b2[2] = 1.0;
            a2[1] = 2.0 * (k * k - 1.0) / a0hp;
            a2[2] = (1.0 - k / q + k * k) / a0hp;
        }

        double process(double x) {
            double y1 = b1[0] * x + b1[1] * x11 + b1[2] * x12 - a1[1] * y11 - a1[2] * y12;
            x12 = x11;
            x11 = x;
            y12 = y11;
            y11 = y1;
            double y2 = b2[0] * y1 + b2[1] * x21 + b2[2] * x22 - a2[1] * y21 - a2[2] * y22;
            x22 = x21;
            x21 = y1;
            y22 = y21;
            y21 = y2;
            return y2;
        }
    }

    /** 100ms 조각 에너지를 모아 400ms 블록(75% 겹침)으로 게이트 적분한다. 400ms가 안 되면 전체를 한 블록으로 본다. */
    static final class Meter {
        private final KWeighting filter;
        private final int subBlockSamples;
        private final List<Double> subBlockEnergy = new ArrayList<>();
        private double currentSum;
        private int currentCount;
        private double totalSum;
        private long totalCount;

        Meter(int sampleRate) {
            this.filter = new KWeighting(sampleRate);
            this.subBlockSamples = Math.max(1, sampleRate / 10);
        }

        /** @return 이 샘플로 100ms 조각 하나가 끝났으면 true */
        boolean add(double sample) {
            double weighted = filter.process(sample);
            double square = weighted * weighted;
            currentSum += square;
            currentCount++;
            totalSum += square;
            totalCount++;
            if (currentCount == subBlockSamples) {
                subBlockEnergy.add(currentSum / currentCount);
                currentSum = 0;
                currentCount = 0;
                return true;
            }
            return false;
        }

        double integrated() {
            return integrated(Integer.MAX_VALUE);
        }

        /** 최근 lastBlocks개 400ms 블록(100ms 간격)만으로 적분한다. */
        double integrated(int lastBlocks) {
            int allBlocks = subBlockEnergy.size() - 3;
            int first = Math.max(0, allBlocks - lastBlocks);
            int blocks = allBlocks - first;
            if (allBlocks <= 0) {
                if (totalCount == 0) {
                    return Double.NEGATIVE_INFINITY;
                }
                double lufs = toLufs(totalSum / totalCount);
                return lufs > ABSOLUTE_GATE_LUFS ? lufs : Double.NEGATIVE_INFINITY;
            }
            double[] energies = new double[blocks];
            for (int index = 0; index < blocks; index++) {
                int sub = first + index;
                energies[index] = (subBlockEnergy.get(sub) + subBlockEnergy.get(sub + 1)
                        + subBlockEnergy.get(sub + 2) + subBlockEnergy.get(sub + 3)) / 4.0;
            }
            double sum = 0;
            int count = 0;
            for (double energy : energies) {
                if (toLufs(energy) > ABSOLUTE_GATE_LUFS) {
                    sum += energy;
                    count++;
                }
            }
            if (count == 0) {
                return Double.NEGATIVE_INFINITY;
            }
            double relativeGate = toLufs(sum / count) + RELATIVE_GATE_LU;
            double gatedSum = 0;
            int gatedCount = 0;
            for (double energy : energies) {
                double lufs = toLufs(energy);
                if (lufs > ABSOLUTE_GATE_LUFS && lufs > relativeGate) {
                    gatedSum += energy;
                    gatedCount++;
                }
            }
            return gatedCount == 0 ? Double.NEGATIVE_INFINITY : toLufs(gatedSum / gatedCount);
        }

        private static double toLufs(double meanSquare) {
            return meanSquare <= 0 ? Double.NEGATIVE_INFINITY : -0.691 + 10.0 * Math.log10(meanSquare);
        }
    }

    private static final class LevelingStream extends InputStream {
        private final InputStream upstream;
        private final double targetLufs;
        private final Meter meter;
        private final double slewPerSample;
        private final int primeSpeechSamples;
        private final int primeMaxSamples;
        private final int runningBlocks = (int) Math.round(RUNNING_WINDOW_SECONDS * 10);
        private final double onsetLevel = Math.pow(10, ONSET_DBFS / 20);
        private final byte[] readBuffer = new byte[8192];
        private int carry = -1;
        private boolean primed;
        private boolean upstreamEnded;
        private double currentGainDb;
        private double desiredGainDb;
        private byte[] out = new byte[0];
        private int outPosition;

        LevelingStream(InputStream upstream, int sampleRate, double targetLufs) {
            this.upstream = upstream;
            this.targetLufs = targetLufs;
            this.meter = new Meter(sampleRate);
            this.slewPerSample = SLEW_DB_PER_SECOND / sampleRate;
            this.primeSpeechSamples = (int) (PRIME_SPEECH_SECONDS * sampleRate);
            this.primeMaxSamples = (int) (PRIME_MAX_SECONDS * sampleRate);
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            while (outPosition >= out.length) {
                if (!fill()) {
                    return -1;
                }
            }
            int copied = Math.min(length, out.length - outPosition);
            System.arraycopy(out, outPosition, buffer, offset, copied);
            outPosition += copied;
            return copied;
        }

        @Override
        public void close() throws IOException {
            upstream.close();
        }

        /** 다음에 내보낼 소리를 out에 채운다. 더 없으면 false. */
        private boolean fill() throws IOException {
            if (!primed) {
                return prime();
            }
            short[] samples = readSamples();
            if (samples == null) {
                return false;
            }
            for (short sample : samples) {
                if (meter.add(sample / 32768.0)) {
                    desiredGainDb = gainDb(meter.integrated(runningBlocks), targetLufs);
                }
            }
            emit(samples);
            return true;
        }

        /** 말소리를 조금 모아 첫 이득을 정하고, 모은 소리를 그 이득으로 내보낸다. */
        private boolean prime() throws IOException {
            List<short[]> held = new ArrayList<>();
            int heldSamples = 0;
            int speechSamples = 0;
            boolean onset = false;
            while (speechSamples < primeSpeechSamples && heldSamples < primeMaxSamples) {
                short[] samples = readSamples();
                if (samples == null) {
                    break;
                }
                for (short sample : samples) {
                    meter.add(sample / 32768.0);
                    if (!onset && Math.abs(sample / 32768.0) >= onsetLevel) {
                        onset = true;
                    }
                    if (onset) {
                        speechSamples++;
                    }
                }
                held.add(samples);
                heldSamples += samples.length;
            }
            primed = true;
            currentGainDb = gainDb(meter.integrated() - ONSET_BIAS_DB, targetLufs);
            desiredGainDb = currentGainDb;
            if (heldSamples == 0) {
                return false;
            }
            short[] all = new short[heldSamples];
            int position = 0;
            for (short[] piece : held) {
                System.arraycopy(piece, 0, all, position, piece.length);
                position += piece.length;
            }
            emit(all);
            return true;
        }

        private void emit(short[] samples) {
            byte[] bytes = new byte[samples.length * 2];
            for (int index = 0; index < samples.length; index++) {
                double step = desiredGainDb - currentGainDb;
                if (step != 0) {
                    currentGainDb += Math.max(-slewPerSample, Math.min(slewPerSample, step));
                }
                putSample(bytes, index * 2, limit(samples[index] / 32768.0 * dbToLinear(currentGainDb)));
            }
            out = bytes;
            outPosition = 0;
        }

        /** 원본에서 한 번 읽어 샘플로 - 홀수 바이트는 다음으로 넘긴다. 끝이면 null. */
        private short[] readSamples() throws IOException {
            while (!upstreamEnded) {
                int read = upstream.read(readBuffer, 0, readBuffer.length);
                if (read == -1) {
                    upstreamEnded = true;
                    break;
                }
                if (read == 0) {
                    continue;
                }
                byte[] bytes;
                if (carry >= 0) {
                    bytes = new byte[read + 1];
                    bytes[0] = (byte) carry;
                    System.arraycopy(readBuffer, 0, bytes, 1, read);
                    carry = -1;
                } else {
                    bytes = Arrays.copyOf(readBuffer, read);
                }
                if (bytes.length % 2 == 1) {
                    carry = bytes[bytes.length - 1] & 0xff;
                }
                short[] samples = toSamples(bytes, bytes.length);
                if (samples.length > 0) {
                    return samples;
                }
            }
            return null;
        }
    }
}

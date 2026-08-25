package com.qstory.backend.provider.audio.service;
import com.qstory.backend.provider.audio.NormalizedAudio;

import com.qstory.backend.config.AppProperties;
import com.qstory.backend.common.error.AbortException;
import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.common.error.ProviderException;
import com.qstory.backend.common.util.RequestDeadline;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** audio-normalizer.mjs를 Java로 포팅한 것. audio/webm은 별도로 실행한 ffmpeg 프로세스를 거치고, 그 외에는 그대로 통과시킨다. */
@Service
public class AudioNormalizer {

    private record DirectFormat(String extension, String mimeType) {}

    private static final Map<String, DirectFormat> DIRECT_FORMATS = Map.of(
            "audio/mp4", new DirectFormat("m4a", "audio/mp4"),
            "audio/m4a", new DirectFormat("m4a", "audio/mp4"),
            "audio/mpeg", new DirectFormat("mp3", "audio/mpeg"),
            "audio/wav", new DirectFormat("wav", "audio/wav"),
            "audio/x-wav", new DirectFormat("wav", "audio/wav"),
            "audio/flac", new DirectFormat("flac", "audio/flac"));

    private final String ffmpegPath;

    public AudioNormalizer(AppProperties config) {
        this.ffmpegPath = config.ffmpegPath();
    }

    public NormalizedAudio normalize(byte[] audio, String sourceMimeType, RequestDeadline deadline) {
        DirectFormat direct = DIRECT_FORMATS.get(sourceMimeType);
        if (direct != null) {
            return new NormalizedAudio(audio, direct.extension(), direct.mimeType(), false);
        }
        if (!"audio/webm".equals(sourceMimeType)) {
            throw new ProviderException(
                    ProviderErrorCode.AUDIO_NORMALIZATION_UNSUPPORTED, "이 녹음 형식은 아직 변환할 수 없어요.", false);
        }
        deadline.requireTimeRemaining();

        Path directory = null;
        try {
            directory = Files.createTempDirectory("qstory-audio-");
            Path inputPath = directory.resolve("question.webm");
            Path outputPath = directory.resolve("question.wav");
            Files.write(inputPath, audio);
            runFfmpeg(inputPath, outputPath, deadline.remaining());
            byte[] normalized = Files.readAllBytes(outputPath);
            if (normalized.length == 0) {
                throw new IOException("normalized audio is empty");
            }
            return new NormalizedAudio(normalized, "wav", "audio/wav", true);
        } catch (AbortException abort) {
            throw abort;
        } catch (Exception error) {
            throw new ProviderException(
                    ProviderErrorCode.AUDIO_NORMALIZATION_FAILED, "녹음 형식을 음성 인식용으로 바꾸지 못했어요.", true, error);
        } finally {
            deleteRecursively(directory);
        }
    }

    private void runFfmpeg(Path inputPath, Path outputPath, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
                ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
                "-i", inputPath.toString(), "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                outputPath.toString());
        builder.redirectErrorStream(false);
        Process process = builder.start();

        ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
        Thread stderrDrain = new Thread(() -> {
            try (var errorStream = process.getErrorStream()) {
                errorStream.transferTo(stderrBuffer);
            } catch (IOException ignored) {
                // 읽는 도중에 프로세스가 강제 종료되었을 수 있다; 그때까지 캡처된 내용만으로도 로그 한 줄로는 충분하다
            }
        }, "ffmpeg-stderr-drain");
        stderrDrain.setDaemon(true);
        stderrDrain.start();

        boolean finished = process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            stderrDrain.join(1_000);
            throw new AbortException("request-timeout");
        }
        stderrDrain.join(1_000);
        if (process.exitValue() != 0) {
            String stderr = stderrBuffer.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
            String detail = stderr.isBlank() ? "ffmpeg exited with " + process.exitValue() : stderr;
            throw new IOException(detail.length() > 1000 ? detail.substring(0, 1000) : detail);
        }
    }

    private void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 최선을 다한 정리(best-effort cleanup)로, Node 구현의 rm(..., { force: true })와 동일하다
                }
            });
        } catch (IOException ignored) {
            // 디렉터리가 이미 사라졌을 수 있다
        }
    }
}

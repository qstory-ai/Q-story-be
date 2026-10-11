package com.qstory.backend.provider.gemini.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini streamGenerateContent(alt=sse) 응답을 raw PCM InputStream으로 바꾼다. 이벤트 하나가
 * {"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/l16; rate=24000; channels=1","data":"<base64>"}}]}}]}
 * 형태라(2026-10 실측, 조각 하나에 약 40ms), 이벤트가 도착하는 대로 디코딩해 내보낸다 - 전체를 모으지 않는다.
 *
 * <p>스트림 중간의 {"error":...} 이벤트나 차단(promptFeedback.blockReason)은 IOException으로 끊는다.
 * 첫 오디오 전이면 GeminiTtsClient가 이를 JSON 실패 응답으로 바꾸고, 이미 내보내기 시작했으면 소리가 거기서 끝난다.
 */
final class GeminiSsePcmStream extends InputStream {

    private static final Pattern RATE = Pattern.compile("rate=(\\d+)");

    /** 스트림이 끝나거나 닫힐 때 한 번 - 호출 기록(하루 한도 집계)용. */
    record Summary(long audioBytes, boolean completed) {}

    private final InputStream upstream;
    private final BufferedReader reader;
    private final ObjectMapper objectMapper;
    private final Consumer<Summary> onFinish;
    private byte[] current = new byte[0];
    private int position;
    private long audioBytes;
    private int sampleRate;
    private boolean ended;
    private boolean finishReported;

    GeminiSsePcmStream(InputStream upstream, ObjectMapper objectMapper, int defaultSampleRate, Consumer<Summary> onFinish) {
        this.upstream = upstream;
        this.reader = new BufferedReader(new InputStreamReader(upstream, StandardCharsets.UTF_8));
        this.objectMapper = objectMapper;
        this.sampleRate = defaultSampleRate;
        this.onFinish = onFinish;
    }

    /** 첫 오디오 조각까지 읽어 둔다. 오디오 없이 끝났으면 false. */
    boolean awaitFirstAudio() throws IOException {
        return position < current.length || advance();
    }

    /** 첫 조각의 mimeType(rate=)에서 읽은 샘플레이트 - 없으면 생성자에 준 기본값. */
    int sampleRate() {
        return sampleRate;
    }

    @Override
    public int read() throws IOException {
        if (position >= current.length && !advance()) {
            return -1;
        }
        return current[position++] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        // 지금 가진 조각만 내준다 - 다음 조각을 기다리느라 이미 받은 소리를 붙잡아 두지 않는다.
        if (position >= current.length && !advance()) {
            return -1;
        }
        int copied = Math.min(length, current.length - position);
        System.arraycopy(current, position, buffer, offset, copied);
        position += copied;
        return copied;
    }

    @Override
    public void close() throws IOException {
        reportFinish(false);
        upstream.close();
    }

    private boolean advance() throws IOException {
        if (ended) {
            return false;
        }
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() > 0 && take(data)) {
                    return true;
                }
                continue;
            }
            if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line.substring(5).trim());
            }
            // event:/id:/주석(:)은 쓰지 않는다.
        }
        if (data.length() > 0 && take(data)) {
            return true;
        }
        ended = true;
        reportFinish(true);
        return false;
    }

    /** 이벤트 하나를 디코딩해 소리가 있으면 current로 삼는다. */
    private boolean take(StringBuilder data) throws IOException {
        String payload = data.toString();
        data.setLength(0);
        byte[] pcm = decodeEvent(payload);
        if (pcm.length == 0) {
            return false;
        }
        current = pcm;
        position = 0;
        audioBytes += pcm.length;
        return true;
    }

    private byte[] decodeEvent(String payload) throws IOException {
        if ("[DONE]".equals(payload)) {
            return new byte[0];
        }
        JsonNode event = objectMapper.readTree(payload);
        if (event.has("error")) {
            throw new IOException("gemini-tts stream error status=" + event.path("error").path("status").asText("")
                    + " code=" + event.path("error").path("code").asText(""));
        }
        String blockReason = event.path("promptFeedback").path("blockReason").asText("");
        if (!blockReason.isEmpty()) {
            throw new IOException("gemini-tts stream blocked reason=" + blockReason);
        }
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (JsonNode candidate : event.path("candidates")) {
            for (JsonNode part : candidate.path("content").path("parts")) {
                JsonNode inline = part.path("inlineData");
                String base64 = inline.path("data").asText("");
                if (base64.isEmpty()) {
                    continue;
                }
                if (audioBytes == 0 && pcm.size() == 0) {
                    Matcher rate = RATE.matcher(inline.path("mimeType").asText(""));
                    if (rate.find()) {
                        sampleRate = Integer.parseInt(rate.group(1));
                    }
                }
                pcm.writeBytes(Base64.getDecoder().decode(base64));
            }
        }
        return pcm.toByteArray();
    }

    private void reportFinish(boolean completed) {
        if (finishReported) {
            return;
        }
        finishReported = true;
        onFinish.accept(new Summary(audioBytes, completed));
    }
}

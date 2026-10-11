package com.qstory.backend.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.common.error.Failure;
import com.qstory.backend.common.error.FailureBody;
import com.qstory.backend.common.util.HttpJsonWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Spring이 바로 JSON으로 묶는(@RequestBody) 엔드포인트 중 로그인 없이도 부를 수 있는 것들의 요청 크기 상한(보안 점검 M5).
 * 바디를 직접 읽는 엔드포인트(beta-events·client-errors·interactions·session-recordings 등)는 컨트롤러가 이미 상한을 둔다.
 * 그 밖의 경로는 Tomcat 기본값(상한 없음)이라 큰 JSON 하나로 메모리를 채울 수 있었다.
 *
 * <p>Content-Length가 상한을 넘으면 바로 413을 준다. 길이를 밝히지 않은(chunked) 요청은 읽는 도중 상한을 넘는 순간 413
 * (ApiException → GlobalExceptionHandler)으로 끊는다.
 */
@Component
@Order(2)
public class JsonBodyLimitFilter extends OncePerRequestFilter {

    static final long SMALL = 64L * 1024;
    /** 대화 줄 50개(줄마다 500자)를 한 번에 보낸다(PlaySessionService.MAX_TURNS_PER_REQUEST). */
    static final long PLAY_TURNS = 256L * 1024;

    record Rule(Pattern path, long maxBytes) {}

    static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("^/v1/play-sessions/[^/]+/turns$"), PLAY_TURNS),
            new Rule(Pattern.compile("^/v1/recording-consents$"), SMALL),
            new Rule(Pattern.compile("^/v1/me/recording-consent$"), SMALL),
            new Rule(Pattern.compile("^/v1/launch-notifications$"), SMALL),
            new Rule(Pattern.compile("^/v1/completion-surveys$"), SMALL),
            new Rule(Pattern.compile("^/v1/me/push-tokens(/remove)?$"), SMALL),
            new Rule(Pattern.compile("^/v1/voice-research/withdraw$"), SMALL),
            new Rule(Pattern.compile("^/v1/me/voice-research-consent(/withdraw)?$"), SMALL));

    private final ObjectMapper objectMapper;

    public JsonBodyLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 이 요청의 상한. 대상이 아니면 -1. */
    static long limitFor(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"POST".equals(method) && !"PUT".equals(method) && !"PATCH".equals(method)) return -1;
        String path = request.getRequestURI();
        for (Rule rule : RULES) {
            if (rule.path().matcher(path).matches()) return rule.maxBytes();
        }
        return -1;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long limit = limitFor(request);
        if (limit < 0) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > limit) {
            HttpJsonWriter.writeJson(response, objectMapper, 413, FailureBody.of(
                    new Failure(ErrorCode.PAYLOAD_TOO_LARGE.name(), "upload", false, "요청이 너무 커요.")));
            return;
        }
        chain.doFilter(new LimitedRequest(request, limit), response);
    }

    /** 읽은 바이트가 상한을 넘는 순간 413으로 끊는다 - Content-Length가 없거나 틀린 요청용. */
    static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long limit;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedStream(super.getInputStream(), limit);
            }
            return stream;
        }
    }

    static final class LimitedStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limit;
        private long read;

        LimitedStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) count(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) count(n);
            return n;
        }

        private void count(int n) {
            read += n;
            if (read > limit) {
                throw ApiException.contractError(ErrorCode.PAYLOAD_TOO_LARGE, "요청이 너무 커요.", 413);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}

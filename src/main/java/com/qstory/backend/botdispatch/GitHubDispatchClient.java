package com.qstory.backend.botdispatch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** GitHub Actions workflow_dispatch 한 번 부르기(동기, 다시 보내지 않음). 성공은 204. 토큰은 어디에도 찍지 않는다. */
@Component
public class GitHubDispatchClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    static final String API = "https://api.github.com";

    /** status는 응답 코드(예외면 -1), error는 예외 이름(응답을 받았으면 null). */
    public record Result(int status, String error) {
        public boolean ok() {
            return status == 204;
        }
    }

    private final HttpClient http;

    /** 생성자가 둘이라 Spring이 쓸 생성자를 명시한다 - 없으면 기본 생성자를 찾다 부팅이 실패한다. */
    @Autowired
    public GitHubDispatchClient() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    GitHubDispatchClient(HttpClient http) {
        this.http = http;
    }

    public Result dispatch(String token, String repo, String workflow, String ref) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri(repo, workflow))
                    .timeout(READ_TIMEOUT)
                    .header("authorization", "Bearer " + token.trim())
                    .header("accept", "application/vnd.github+json")
                    .header("x-github-api-version", "2022-11-28")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(ref), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return new Result(response.statusCode(), null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Result(-1, "InterruptedException");
        } catch (Exception failure) {
            return new Result(-1, failure.getClass().getSimpleName());
        }
    }

    static URI uri(String repo, String workflow) {
        return URI.create(API + "/repos/" + repo.trim() + "/actions/workflows/" + workflow.trim() + "/dispatches");
    }

    static String body(String ref) {
        // ref는 설정값(브랜치 이름)이라 JSON을 깨는 따옴표·역슬래시만 뺀다.
        return "{\"ref\":\"" + ref.replace("\\", "").replace("\"", "") + "\"}";
    }
}

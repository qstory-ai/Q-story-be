package com.qstory.backend.completionsurvey.controller;

import com.qstory.backend.completionsurvey.dto.CompletionSurveySubmission;
import com.qstory.backend.completionsurvey.service.CompletionSurveyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 완주 후 부모 리포트 화면에서 남기는 "1분 체험 후기"를 받는다. 기존에는 이 문항들을 외부
 * Google Form으로 리다이렉트해서 받았지만, 인앱 모달(CompletionSurveyModal.tsx)로 대체하며
 * 생긴 엔드포인트다 - LaunchNotificationController와 같은 "인증 불필요" 원칙을 따르는 완전
 * 익명 제출이다.
 */
@Tag(name = "Completion surveys", description = "Anonymous post-demo experience survey")
@RestController
public class CompletionSurveyController {

    private final CompletionSurveyService service;

    public CompletionSurveyController(CompletionSurveyService service) {
        this.service = service;
    }

    @Operation(
            summary = "Submit a post-demo completion survey",
            description = "No authentication required. Mirrors the Q-Story completion survey questions.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Saved"),
            @ApiResponse(responseCode = "400", description = "Malformed or missing field")
    })
    @PostMapping("/v1/completion-surveys")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submit(@RequestBody CompletionSurveySubmission submission) {
        service.submit(submission);
    }
}

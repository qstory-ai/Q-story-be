package com.qstory.backend.feedback.controller;

import com.qstory.backend.feedback.dto.SubmitFeedbackRequest;
import com.qstory.backend.feedback.service.FeedbackService;
import com.qstory.backend.identity.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 마이페이지 "개선사항 요청" - 로그인한 사용자만 제출할 수 있다(역할 무관). */
@Tag(name = "Feedback", description = "In-app improvement-request submissions")
@RestController
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final CurrentUserResolver currentUserResolver;

    public FeedbackController(FeedbackService feedbackService, CurrentUserResolver currentUserResolver) {
        this.feedbackService = feedbackService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Submit an improvement request", description = "Requires login; any role may submit.")
    @PostMapping("/v1/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    public void submit(@RequestBody SubmitFeedbackRequest request) {
        feedbackService.submit(currentUserResolver.require(), request);
    }
}

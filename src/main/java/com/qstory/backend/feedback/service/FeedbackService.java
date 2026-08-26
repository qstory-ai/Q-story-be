package com.qstory.backend.feedback.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.feedback.dto.SubmitFeedbackRequest;
import com.qstory.backend.feedback.entity.ImprovementFeedback;
import com.qstory.backend.feedback.repository.ImprovementFeedbackRepository;
import com.qstory.backend.identity.security.CurrentUser;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackService {

    private static final int MAX_MESSAGE_LENGTH = 2000;

    private final ImprovementFeedbackRepository repository;

    public FeedbackService(ImprovementFeedbackRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void submit(CurrentUser caller, SubmitFeedbackRequest request) {
        String message = request.message() == null ? "" : request.message().trim();
        if (message.isEmpty() || message.length() > MAX_MESSAGE_LENGTH) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "의견을 입력해 주세요.");
        }
        repository.save(ImprovementFeedback.builder()
                .userId(caller.userId())
                .message(message)
                .createdAt(Instant.now())
                .build());
    }
}

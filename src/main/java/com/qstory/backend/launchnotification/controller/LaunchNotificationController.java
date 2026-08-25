package com.qstory.backend.launchnotification.controller;

import com.qstory.backend.launchnotification.dto.LaunchNotificationSubmission;
import com.qstory.backend.launchnotification.service.LaunchNotificationService;
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
 * 무료 데모(fe/q-story-web `/demo`)를 쓰기 전, 정식 출시 때 연락받고 싶다는 보호자 신청을
 * 받는다. 로그인 계정과 무관한 완전 익명 제출 - StoryController/QuestionController와 같은
 * "인증 불필요" 원칙을 따르되, 여기서는 실제로 이름·이메일·전화번호를 저장한다는 점이 다르다
 * (LaunchNotificationService의 서버 측 재검증 참고).
 */
@Tag(name = "Launch notifications", description = "Anonymous pre-demo contact capture for the public launch")
@RestController
public class LaunchNotificationController {

    private final LaunchNotificationService service;

    public LaunchNotificationController(LaunchNotificationService service) {
        this.service = service;
    }

    @Operation(
            summary = "Submit a launch-notification request",
            description = "No authentication required. Body: {parentName, email, phone, childGender, childAge, "
                    + "discoverySource}. childGender is one of BOY/GIRL/UNSPECIFIED.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Saved"),
            @ApiResponse(responseCode = "400", description = "Malformed or missing field")
    })
    @PostMapping("/v1/launch-notifications")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submit(@RequestBody LaunchNotificationSubmission submission) {
        service.submit(submission);
    }
}

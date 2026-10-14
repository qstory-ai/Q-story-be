package com.qstory.backend.partnership.controller;

import com.qstory.backend.partnership.dto.PartnershipInquirySubmission;
import com.qstory.backend.partnership.service.PartnershipInquiryService;
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
 * 소개 페이지(qstory.ai.kr) "협업하기" 문의. 인증 불필요 - LaunchNotificationController와 같은 익명 제출이며
 * 요청 상한(RateLimitRule.PARTNERSHIP_INQUIRIES)과 본문 상한(JsonBodyLimitFilter SMALL)을 둔다.
 * 랜딩은 데모 앱 프록시(demo.qstory.ai.kr/api/qstory/v1/partnership-inquiries, 랜딩 출처 CORS 허용)로 부른다.
 */
@Tag(name = "Partnership inquiries", description = "Anonymous partnership inquiry from the landing page")
@RestController
public class PartnershipInquiryController {

    private final PartnershipInquiryService service;

    public PartnershipInquiryController(PartnershipInquiryService service) {
        this.service = service;
    }

    @Operation(
            summary = "Submit a partnership inquiry",
            description = "No authentication required. Body: {name, affiliation, role, jobTitle?, email, phone?, message, "
                    + "privacyConsent}. role is one of ORGANIZATION/PUBLISHER/OTHER; privacyConsent must be true.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Saved"),
            @ApiResponse(responseCode = "400", description = "Malformed or missing field")
    })
    @PostMapping("/v1/partnership-inquiries")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submit(@RequestBody PartnershipInquirySubmission submission) {
        service.submit(submission);
    }
}

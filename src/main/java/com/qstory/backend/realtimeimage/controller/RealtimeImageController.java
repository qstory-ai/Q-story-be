package com.qstory.backend.realtimeimage.controller;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.realtimeimage.entity.RealtimeImage;
import com.qstory.backend.realtimeimage.service.RealtimeImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시도 장면 막힘 그림이 다 만들어졌는지 프런트가 지켜보는 표면(companion-chat 응답의 dialogue.attempt.imageJobId).
 * 새 선택지 그림은 여기 말고 live-branch 작업과 스토리 패키지 재조회로 간다. 아이 대상 공개 엔드포인트라 역할 게이트가 없고,
 * 그림 주소 말고는 아무것도 내려 주지 않는다.
 */
@Tag(name = "Realtime image", description = "Poll a real-time illustration (attempt-scene backdrop)")
@RestController
@RequestMapping("/v1/realtime-images")
public class RealtimeImageController {

    private final RealtimeImageService service;

    public RealtimeImageController(RealtimeImageService service) {
        this.service = service;
    }

    @Operation(summary = "Poll a real-time illustration",
            description = "Returns {status, imageUrl?, errorCode?}. status is QUEUED/GENERATING/READY/FAILED; imageUrl only once READY.")
    @GetMapping("/{id}")
    public Map<String, Object> get(@Parameter(description = "realtime_image.id") @PathVariable UUID id) {
        RealtimeImage image = service.find(id)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "그 그림 작업을 찾지 못했어요."));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", image.getStatus().name());
        if (image.getImageUrl() != null) {
            body.put("imageUrl", image.getImageUrl());
        }
        if (image.getErrorCode() != null) {
            body.put("errorCode", image.getErrorCode());
        }
        return body;
    }
}

package com.qstory.backend.playsession.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.playsession.service.PlaySessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 플레이 중 대화 기록(Q-39) - 부모 리포트의 "아이가 남긴 말"이 이 기록을 그대로 보여 준다. */
@Tag(name = "Play sessions", description = "Turn-by-turn dialogue log of one story play session, read back by the report")
@RestController
public class PlaySessionController {

    private final PlaySessionService service;
    private final CurrentUserResolver currentUserResolver;

    public PlaySessionController(PlaySessionService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(
            summary = "Append dialogue turns to a play session",
            description = "Creates the session on first call (owned by the caller). Idempotent per (sessionId, seq). "
                    + "At most 50 turns per call, text up to 500 characters.")
    @PostMapping("/v1/play-sessions/{sessionId}/turns")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> appendTurns(@PathVariable UUID sessionId, @RequestBody JsonNode body) {
        int savedThrough = service.appendTurns(currentUserResolver.require(), sessionId, body);
        return Map.of("ok", true, "savedThrough", savedThrough);
    }
}

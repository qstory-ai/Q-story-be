package com.qstory.backend.sessionrecording.controller;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.sessionrecording.service.SessionRecordingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 팀 내부 UT 화면 녹화 재생. 아이가 쓰는 화면 전체가 담기므로 Role.STAFF만 읽는다 - STAFF는 공개 가입으로 얻을 수
 * 없는 내부 운영 역할이다(Role, SecurityConfig 참고). 기관의 원장·선생님(DIRECTOR/TUTOR)은 셀프 가입 역할이라 쓰지 않는다.
 */
@Tag(name = "Session recordings (internal)", description = "STAFF-only replay of UT screen recordings")
@RestController
public class SessionRecordingAdminController {

    private final SessionRecordingService service;
    private final CurrentUserResolver currentUserResolver;

    public SessionRecordingAdminController(SessionRecordingService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(
            summary = "Find recorded beta sessions by code",
            description = "STAFF only. code = 6 hex chars: a beta session code or a play session code (first 6 hex "
                    + "of the id). Returns a JSON array, newest first, at most 50.")
    @GetMapping("/v1/admin/session-recordings")
    public List<Map<String, Object>> find(@RequestParam("code") String code) {
        currentUserResolver.requireRole(Role.STAFF);
        return service.findSessions(code);
    }

    @Operation(
            summary = "Chunks of one recorded beta session, paged",
            description = "STAFF only. Ordered by seq, chunks with seq > afterSeq (default -1), about 2.5 MB per page. "
                    + "nextAfterSeq is the afterSeq for the next page, or null at the end. gzip-base64 chunks come back "
                    + "base64-encoded, json chunks as the raw string.")
    @GetMapping("/v1/admin/session-recordings/{betaSessionId}/chunks")
    public Map<String, Object> chunks(
            @PathVariable UUID betaSessionId, @RequestParam(value = "afterSeq", defaultValue = "-1") int afterSeq) {
        currentUserResolver.requireRole(Role.STAFF);
        return service.chunkPage(betaSessionId, afterSeq);
    }
}

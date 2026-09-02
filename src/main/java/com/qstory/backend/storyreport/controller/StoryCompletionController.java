package com.qstory.backend.storyreport.controller;

import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.storyreport.dto.RecordStoryCompletionRequest;
import com.qstory.backend.storyreport.dto.StoryCompletionDetail;
import com.qstory.backend.storyreport.dto.StoryCompletionSummary;
import com.qstory.backend.storyreport.service.StoryCompletionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 보호자의 과거 "오늘의 질문 기록" 리포트 - 목록/상세/기록 조회 모두 항상 호출자 본인 것으로만 제한된다. */
@Tag(name = "Story completions", description = "Saved parent reports from finished story sessions")
@RestController
public class StoryCompletionController {

    private final StoryCompletionService service;
    private final CurrentUserResolver currentUserResolver;

    public StoryCompletionController(StoryCompletionService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "Save a finished story session's report",
            description = "outcomes is the same derived per-question summary the report screen itself is built "
                    + "from - never a raw recording or transcript.")
    @PostMapping("/v1/story-completions")
    @ResponseStatus(HttpStatus.CREATED)
    public StoryCompletionSummary record(@RequestBody RecordStoryCompletionRequest request) {
        return service.record(currentUserResolver.require(), request);
    }

    @Operation(summary = "List the caller's past reports, newest first",
            description = "childId query param을 주면 그 아이 프로필로 진행한 세션만 반환. legacy 기록·튜터 세션은 제외.")
    @GetMapping("/v1/story-completions")
    public List<StoryCompletionSummary> list(@RequestParam(required = false) UUID childId) {
        return service.list(currentUserResolver.require(), childId);
    }

    @Operation(summary = "Get one past report's full detail")
    @GetMapping("/v1/story-completions/{id}")
    public StoryCompletionDetail get(@PathVariable UUID id) {
        return service.get(currentUserResolver.require(), id);
    }

    @Operation(summary = "List the caller's most recent reports with full outcomes, newest first",
            description = "For cross-session trend views (repeated approaches, recurring interests) - capped at 20. "
                    + "childId를 주면 그 아이만.")
    @GetMapping("/v1/story-completions/recent")
    public List<StoryCompletionDetail> recent(
            @RequestParam(defaultValue = "5") int limit, @RequestParam(required = false) UUID childId) {
        return service.recent(currentUserResolver.require(), limit, childId);
    }
}

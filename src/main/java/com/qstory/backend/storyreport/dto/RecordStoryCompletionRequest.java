package com.qstory.backend.storyreport.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** tutorStudentId는 선택값이다 - 방문 선생님이 자신이 등록한 학생과 진행한 세션일 때만 채운다. */
public record RecordStoryCompletionRequest(
        String storyId, Integer durationSeconds, List<Map<String, Object>> outcomes, UUID tutorStudentId) {}

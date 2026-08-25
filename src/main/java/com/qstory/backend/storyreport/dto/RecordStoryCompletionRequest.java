package com.qstory.backend.storyreport.dto;

import java.util.List;
import java.util.Map;

public record RecordStoryCompletionRequest(
        String storyId, Integer durationSeconds, List<Map<String, Object>> outcomes) {}

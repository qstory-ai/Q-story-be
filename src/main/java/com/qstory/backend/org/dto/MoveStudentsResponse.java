package com.qstory.backend.org.dto;

import java.util.List;
import java.util.UUID;

public record MoveStudentsResponse(List<UUID> moved, List<SkippedStudent> skipped) {}

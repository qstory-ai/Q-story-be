package com.qstory.backend.org.dto;

import java.util.List;
import java.util.UUID;

/** 학기 넘기기 결과(076) - 학생 id 목록. archived는 이 반을 보관했는지. */
public record TermTransitionResponse(
        List<UUID> moved, List<UUID> kept, List<UUID> graduated, boolean archived, List<SkippedStudent> skipped) {}

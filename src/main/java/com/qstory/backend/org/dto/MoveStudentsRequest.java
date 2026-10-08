package com.qstory.backend.org.dto;

import java.util.List;
import java.util.UUID;

/** 학생 반 옮기기(076) - 같은 기관의 지난 반이 아닌 반으로. */
public record MoveStudentsRequest(List<UUID> studentIds, UUID targetClassId) {}

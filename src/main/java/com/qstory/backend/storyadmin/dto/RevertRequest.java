package com.qstory.backend.storyadmin.dto;

/** 지정한 리비전이 변경한 내용을, 그 수정이 있기 전 상태로 복원한다. */
public record RevertRequest(Integer baseRevision, Integer revision, String summary) {}

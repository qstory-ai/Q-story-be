package com.qstory.backend.storyadmin.dto;

/** Restores whatever the named revision changed, back to the state it held before that edit. */
public record RevertRequest(Integer baseRevision, Integer revision, String summary) {}

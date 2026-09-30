package com.qstory.backend.org.dto;

import java.util.UUID;

/** homeroomTutorId: 이 기관에 소속된 선생님. 비우면 "담임 미정"으로 만들고 나중에 배정한다. */
public record CreateClassRequest(String name, UUID homeroomTutorId) {}

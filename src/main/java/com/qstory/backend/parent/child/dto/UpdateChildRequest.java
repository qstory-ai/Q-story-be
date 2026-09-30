package com.qstory.backend.parent.child.dto;

/**
 * 부분 업데이트 - null인 필드는 그대로 두고 값이 있는 필드만 반영한다. birthYear를 보내면 ageBand도
 * 그에 맞춰 다시 계산된다.
 */
public record UpdateChildRequest(String name, String ageBand, String avatarKey, String gender, Integer birthYear) {}

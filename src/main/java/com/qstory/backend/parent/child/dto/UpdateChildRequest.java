package com.qstory.backend.parent.child.dto;

/**
 * 부분 업데이트 - null인 필드는 그대로 두고 값이 있는 필드만 반영한다. 지원하는 필드 자체는
 * 생성 요청과 같지만, 아이 프로필은 name 하나만 바꾸는 경우가 흔해 이렇게 분리해 둔다.
 */
public record UpdateChildRequest(String name, String ageBand, String avatarKey, String gender) {}

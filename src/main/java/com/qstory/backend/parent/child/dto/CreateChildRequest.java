package com.qstory.backend.parent.child.dto;

/** birthYear(~년생)가 있으면 ageBand는 서버가 계산한다(ChildAge). 둘 다 없으면 400. */
public record CreateChildRequest(String name, String ageBand, String avatarKey, String gender, Integer birthYear) {}

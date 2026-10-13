package com.qstory.backend.org.dto;

import java.util.UUID;

/**
 * 기관 등록. publicInstitutionId(공공 기관 목록에서 고른 유치원·어린이집)가 있으면 그 이름으로 등록하고 name은 무시한다.
 * 없으면 예전처럼 직접 입력한 name으로 등록한다.
 */
public record CreateOrganizationRequest(String name, UUID publicInstitutionId) {}

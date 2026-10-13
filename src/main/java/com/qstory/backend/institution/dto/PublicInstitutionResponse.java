package com.qstory.backend.institution.dto;

import java.util.UUID;

/**
 * 검색 결과 한 곳. registered/organizationId는 로그인한 원장·선생님에게만 채운다(익명 검색 - 원장 가입 화면 - 에는 null) -
 * 어느 유치원이 Q-Story를 쓰는지는 공공 정보가 아니라서다.
 *
 * @param organizationId 이미 Q-Story에 등록된 기관이면 그 기관 id(선생님이 소속 신청할 곳)
 */
public record PublicInstitutionResponse(
        UUID id,
        String kind,
        String name,
        String address,
        String sido,
        String sigungu,
        String establishmentType,
        Boolean registered,
        UUID organizationId) {}

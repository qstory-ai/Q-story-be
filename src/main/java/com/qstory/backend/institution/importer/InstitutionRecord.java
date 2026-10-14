package com.qstory.backend.institution.importer;

import com.qstory.backend.institution.InstitutionKind;

/**
 * 원천 파일 한 줄을 다듬은 결과 - public_institution 한 행. 사람 이름·전화번호는 여기 오기 전에 버린다.
 *
 * @param active 원천이 "운영 중"으로 보는지(어린이집 폐지·휴지는 false)
 */
public record InstitutionRecord(
        InstitutionKind kind,
        String source,
        String sourceKey,
        String name,
        String address,
        String sido,
        String sigungu,
        String establishmentType,
        boolean active) {}

package com.qstory.backend.org.dto;

import java.util.UUID;

/**
 * 기관 등록.
 * <ul>
 *   <li>publicInstitutionId - 공공 기관 목록에서 고른 유치원·어린이집(083). 있으면 그 이름으로 등록하고 name은 무시한다.
 *       없으면 예전처럼 직접 입력한 name으로 등록한다.</li>
 *   <li>guardianConsentAck - "원아의 보호자 동의를 받아 이용해요(기관이 보호자에게 안내·동의를 받습니다)" 확인(필수, A5).
 *       목록에서 고른 등록도 같은 확인이 있어야 한다.</li>
 *   <li>consentVersion - 그 문구의 약관 버전(예: 2026-10-v2).</li>
 * </ul>
 */
public record CreateOrganizationRequest(
        String name, Boolean guardianConsentAck, String consentVersion, UUID publicInstitutionId) {}

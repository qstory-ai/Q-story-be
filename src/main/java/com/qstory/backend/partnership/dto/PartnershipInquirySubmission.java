package com.qstory.backend.partnership.dto;

/**
 * 소개 페이지 협업 문의 폼. role(문의 유형)은 ORGANIZATION(교육기관)/PUBLISHER(출판사·콘텐츠)/OTHER(기타),
 * jobTitle(직급/역할)·phone은 선택, privacyConsent(개인정보 수집·이용 동의)는 true여야 한다.
 */
public record PartnershipInquirySubmission(
        String name,
        String affiliation,
        String role,
        String jobTitle,
        String email,
        String phone,
        String message,
        Boolean privacyConsent) {}

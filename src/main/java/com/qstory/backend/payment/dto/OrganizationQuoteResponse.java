package com.qstory.backend.payment.dto;

/**
 * 기관 이용권 견적. studentCount는 과금 대상(학부모가 연결된 학생), rosterStudentCount는 반 명단 전체 학생 수다.
 * amount = studentCount x unitAmount이고, unitAmount가 0이면 아직 금액이 설정되지 않은 것이다. currentSeats는 지금
 * 결제돼 있는 인원(예전 정액 구독이면 null).
 */
public record OrganizationQuoteResponse(
        int studentCount, int rosterStudentCount, int unitAmount, int amount, Integer currentSeats, int accessDays) {}

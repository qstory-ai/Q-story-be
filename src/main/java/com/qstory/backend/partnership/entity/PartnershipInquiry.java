package com.qstory.backend.partnership.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * 소개 페이지 "협업하기"로 기관·출판사가 남긴 협업 문의 한 건(db/schema/087). 익명 제출이라 AppUser와 연결되지 않는다.
 * 보관 목적은 문의 응대(연락), 기간은 수집일로부터 1년 - PartnershipInquiryRetentionScheduler가 지운다.
 */
@Entity
@Table(name = "partnership_inquiries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartnershipInquiry {

    public enum Role {
        ORGANIZATION("교육기관"),
        PUBLISHER("출판사·콘텐츠"),
        OTHER("기타");

        private final String label;

        Role(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, length = 100)
    private String affiliation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    /** 직급/역할(선택) - 비우면 null. */
    @Column(length = 60)
    private String jobTitle;

    @Column(nullable = false, length = 254)
    private String email;

    /** 선택 - 비우면 null. */
    @Column(length = 30)
    private String phone;

    @Column(nullable = false, length = 2000)
    private String message;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}

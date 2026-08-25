package com.qstory.backend.launchnotification.entity;

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
 * 무료 데모(`/demo`)를 쓰기 전에 남기는, 정식 출시 때 연락받고 싶다는 보호자 신청 한 건.
 * 익명 방문자가 자발적으로 남기는 연락처라 로그인 계정과 연결되지 않는다 - AppUser와는
 * 별개다. 전화 연락에는 쓰지 않는다는 것을 프론트엔드 폼 문구로 명시한다
 * (LaunchNotificationGateModal 참고) - 이메일/문자로만 출시 소식을 안내하는 용도다.
 */
@Entity
@Table(name = "launch_notification_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LaunchNotificationRequest {

    public enum ChildGender {
        BOY,
        GIRL,
        UNSPECIFIED
    }

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, length = 60)
    private String parentName;

    /** 연락 받기를 원치 않는 보호자("괜찮아요")는 비워 둘 수 있다. */
    @Column(length = 254)
    private String email;

    @Column(nullable = false, length = 30)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChildGender childGender;

    /** "5세", "3개월"처럼 자유 텍스트다 - 돌 전 아이는 개월 수로 말하는 경우가 많아 숫자로 강제하지 않는다. */
    @Column(nullable = false, length = 20)
    private String childAge;

    @Column(nullable = false, length = 200)
    private String discoverySource;

    /**
     * "연락 받고 싶어요"(true)와 "괜찮아요"(false) 둘 다 정보는 동일하게 받는다 - 차이는 이
     * 값뿐이다. false여도 출시 안내를 위해 능동적으로 연락하지는 않지만, 신청 자체는 남는다.
     */
    @Column(nullable = false)
    private boolean wantsContact;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}

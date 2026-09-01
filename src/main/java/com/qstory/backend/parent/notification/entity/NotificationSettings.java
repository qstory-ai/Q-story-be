package com.qstory.backend.parent.notification.entity;

import com.qstory.backend.identity.entity.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 사용자별 알림 preference를 담는 sidecar 테이블 - app_user에 컬럼을 늘리지 않고 별도 테이블에
 * 둔 이유는 db/schema/031-notification-settings.sql 헤더 참조. 지금은 marketing_enabled 하나뿐
 * 이라 굳이 엔티티까지 만들지 않아도 되지만, 곧 항목이 늘 것을 감안해 지금부터 도메인 자리를
 * 잡아 둔다.
 */
@Entity
@Table(name = "notification_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationSettings {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    /**
     * 부모/사용자 1:1 - PK를 공유하는 shared-PK 매핑. @MapsId가 user.id를 이 엔티티의 userId로
     * 자동 채워 준다. 삭제는 app_user cascade로 함께 지워진다.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "user_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser user;

    /** IA "마이페이지 > 알림 설정 > 마케팅 알림 (새 작품 출시)". 기본값은 true(opt-in). */
    @Column(name = "marketing_enabled", nullable = false)
    @Builder.Default
    private boolean marketingEnabled = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

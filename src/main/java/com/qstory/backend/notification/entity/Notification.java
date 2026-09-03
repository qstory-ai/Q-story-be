package com.qstory.backend.notification.entity;

import com.qstory.backend.identity.entity.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * 앱 내 알림. NotificationPublisher가 각 도메인 이벤트(예: 튜터가 리포트를 저장했다)에서
 * 이 테이블에 적재하고, NotificationController가 사용자별로 최신순으로 반환한다. 스키마
 * 배경은 db/schema/038-notifications.sql 헤더 참고.
 *
 * <p>dedupKey가 non-null이면 (user, dedupKey) 유니크 제약이 걸려 같은 이벤트가 두 번 발행돼도
 * DB가 거부한다. 프로듀서는 도메인 이벤트마다 안정적인 키(예: `"tutor-report:{uuid}"`)를
 * 정하고, service.publish()가 중복 발행을 조용히 넘긴다.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser user;

    /** 이벤트 종류 - FE가 아이콘·톤을 고르는 데 쓴다. 예: `tutor-report`, `invite-accepted`. */
    @Column(name = "kind", nullable = false, length = 48)
    private String kind;

    @Column(name = "title", nullable = false, length = 160)
    private String title;

    @Column(name = "body", length = 400)
    private String body;

    /** FE 라우터 경로. null이면 알림을 눌러도 이동하지 않고 읽음 표시만 남는다. */
    @Column(name = "href", length = 255)
    private String href;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 중복 발행 방지용 안정 키 - null이면 유니크 제약을 걸지 않는다. */
    @Column(name = "dedup_key", length = 160)
    private String dedupKey;
}

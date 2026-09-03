package com.qstory.backend.notification.service;

import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.notification.entity.Notification;
import com.qstory.backend.notification.repository.NotificationRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도메인 이벤트(예: 튜터가 리포트를 저장했다, 부모가 초대를 수락했다)에서 알림을 발행하기 위한
 * 얇은 헬퍼. 각 도메인 서비스가 이 컴포넌트를 주입받아 publish()를 호출한다.
 *
 * <p>dedupKey를 지정하면 (user, dedupKey) 유니크 제약이 걸린 상태에서 이미 존재하는 알림은
 * 조용히 건너뛴다 - 프로듀서가 트랜잭션 재시도로 두 번 호출되거나, 같은 이벤트를 두 경로에서
 * 발행해도 사용자 벨에는 하나만 남는다.
 */
@Component
public class NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationPublisher.class);

    private final NotificationRepository notificationRepository;
    private final AppUserRepository userRepository;

    public NotificationPublisher(
            NotificationRepository notificationRepository, AppUserRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
    }

    /**
     * @param userId 알림을 받을 대상
     * @param kind 이벤트 종류(FE 아이콘/톤 매핑용). 예: `tutor-report`, `invite-accepted`
     * @param title 벨/드로어에서 굵게 뜨는 한 줄
     * @param body 부가 설명(선택)
     * @param href 클릭 시 앱 내부 이동 경로(선택)
     * @param dedupKey 중복 방지 키(선택). null이면 매번 새 알림을 만든다.
     */
    @Transactional
    public void publish(
            UUID userId, String kind, String title, String body, String href, String dedupKey) {
        if (dedupKey != null) {
            Optional<Notification> existing =
                    notificationRepository.findByUser_IdAndDedupKey(userId, dedupKey);
            if (existing.isPresent()) {
                log.debug("notification.dedup-skip userId={} dedupKey={}", userId, dedupKey);
                return;
            }
        }
        AppUser user = userRepository.getReferenceById(userId);
        Notification saved = notificationRepository.save(Notification.builder()
                .id(UUID.randomUUID())
                .user(user)
                .kind(kind)
                .title(title)
                .body(body)
                .href(href)
                .createdAt(Instant.now())
                .dedupKey(dedupKey)
                .build());
        log.debug("notification.published id={} userId={} kind={}", saved.getId(), userId, kind);
    }
}

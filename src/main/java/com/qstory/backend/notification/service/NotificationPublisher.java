package com.qstory.backend.notification.service;

import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.notification.entity.Notification;
import com.qstory.backend.notification.repository.NotificationRepository;
import com.qstory.backend.parent.notification.repository.NotificationSettingsRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
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

    // kind → notification_settings의 어느 토글이 이 kind를 관장하는지. 여기 없는 kind(초대 수락
    // 등 계정 이벤트류)는 끌 수 없는 알림으로 취급해 항상 보낸다 - marketing_enabled처럼 아직
    // 아무 발신자도 안 걸려 있는 토글은 이 표에 넣을 이유가 없다(발신자가 생기면 그때 추가).
    private static final Set<String> LESSON_REMINDER_KINDS = Set.of("lesson-reminder");
    private static final Set<String> LESSON_REPORT_KINDS = Set.of("tutor-report", "lesson-report");

    private final NotificationRepository notificationRepository;
    private final AppUserRepository userRepository;
    private final NotificationSettingsRepository notificationSettingsRepository;

    public NotificationPublisher(
            NotificationRepository notificationRepository,
            AppUserRepository userRepository,
            NotificationSettingsRepository notificationSettingsRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.notificationSettingsRepository = notificationSettingsRepository;
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
        if (!isEnabled(userId, kind)) {
            log.debug("notification.settings-skip userId={} kind={}", userId, kind);
            return;
        }
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

    /**
     * kind가 LESSON_REMINDER_KINDS/LESSON_REPORT_KINDS에 속하면 그 사용자의 해당 토글을 따르고,
     * 아직 preference 행이 없으면(가입 직후) 기본값 true로 간주한다(마이그레이션 DEFAULT와 일치).
     * 표에 없는 kind는 끌 수 없는 알림으로 취급해 항상 true.
     */
    private boolean isEnabled(UUID userId, String kind) {
        if (LESSON_REMINDER_KINDS.contains(kind)) {
            return notificationSettingsRepository.findById(userId)
                    .map(settings -> settings.isLessonReminderEnabled())
                    .orElse(true);
        }
        if (LESSON_REPORT_KINDS.contains(kind)) {
            return notificationSettingsRepository.findById(userId)
                    .map(settings -> settings.isLessonReportEnabled())
                    .orElse(true);
        }
        return true;
    }
}

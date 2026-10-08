package com.qstory.backend.org.service;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.entity.ClassHomeroomInvite;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.ClassHomeroomInviteRepository;
import com.qstory.backend.org.tutor.entity.OrganizationTutorInvite;
import com.qstory.backend.org.tutor.repository.OrganizationTutorInviteRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 초대 코드 만료를 원장에게 알린다 - 매일 한 번(10:00 KST).
 * <ul>
 *   <li>쓰지 않은 담임 초대가 만료됐으면 "담임 초대 코드가 만료됐어요"(invite-expired). 만료 전에 새 코드로 바뀐 초대는
 *       빼고(원장이 이미 새 코드를 만들었다), 만료 뒤에 바뀐 것은 넣는다.</li>
 *   <li>쓰지 않은 기관 선생님 초대가 만료됐으면 "선생님 초대 코드가 만료됐어요"(invite-expired).</li>
 *   <li>살아 있는 담임 초대가 이틀 안에 만료되면 "이틀 뒤 만료돼요"(invite-expiring).</li>
 * </ul>
 * 만료는 지난 3일을 돌아본다 - 재시작으로 하루가 빠져도 놓치지 않는다. 같은 초대로 알림이 두 번 가지 않게 하는 건
 * NotificationPublisher의 dedupKey다(두 인스턴스가 동시에 돌아도 마찬가지).
 */
@Component
public class InviteExpiryNotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(InviteExpiryNotificationScheduler.class);
    static final Duration EXPIRED_LOOKBACK = Duration.ofDays(3);
    static final Duration EXPIRING_LEAD = Duration.ofDays(2);

    private final ClassHomeroomInviteRepository homeroomInviteRepository;
    private final OrganizationTutorInviteRepository organizationTutorInviteRepository;
    private final AppUserRepository userRepository;
    private final NotificationPublisher notificationPublisher;

    public InviteExpiryNotificationScheduler(
            ClassHomeroomInviteRepository homeroomInviteRepository,
            OrganizationTutorInviteRepository organizationTutorInviteRepository,
            AppUserRepository userRepository,
            NotificationPublisher notificationPublisher) {
        this.homeroomInviteRepository = homeroomInviteRepository;
        this.organizationTutorInviteRepository = organizationTutorInviteRepository;
        this.userRepository = userRepository;
        this.notificationPublisher = notificationPublisher;
    }

    @Scheduled(cron = "0 0 1 * * *", zone = "UTC")
    @Transactional
    public void notifyInviteExpiry() {
        Instant now = Instant.now();
        int sent = 0;
        for (ClassHomeroomInvite invite : homeroomInviteRepository.findExpiredUnusedBetween(now.minus(EXPIRED_LOOKBACK), now)) {
            ClassGroup classGroup = invite.getClassGroup();
            sent += notifyDirector(classGroup.getOrganization(),
                    "invite-expired",
                    NotificationText.title(classGroup.getName() + " 담임 초대 코드가 만료됐어요"),
                    "반 화면에서 새 코드를 만들어 보내 주세요.",
                    "/organization/classes/" + classGroup.getId(),
                    "homeroom-invite-expired:" + invite.getId());
        }
        for (OrganizationTutorInvite invite : organizationTutorInviteRepository.findExpiredUnusedBetween(now.minus(EXPIRED_LOOKBACK), now)) {
            sent += notifyDirector(invite.getOrganization(),
                    "invite-expired",
                    "선생님 초대 코드가 만료됐어요",
                    "선생님 관리에서 새 코드를 만들어 보내 주세요.",
                    "/organization/tutors",
                    "org-invite-expired:" + invite.getId());
        }
        for (ClassHomeroomInvite invite : homeroomInviteRepository.findLiveExpiringBetween(now, now.plus(EXPIRING_LEAD))) {
            ClassGroup classGroup = invite.getClassGroup();
            sent += notifyDirector(classGroup.getOrganization(),
                    "invite-expiring",
                    NotificationText.title(classGroup.getName() + " 담임 초대 코드가 이틀 뒤 만료돼요"),
                    "선생님이 아직 수락하지 않았어요. 기한이 지나면 반 화면에서 새 코드를 만들어 주세요.",
                    "/organization/classes/" + classGroup.getId(),
                    "homeroom-invite-expiring:" + invite.getId());
        }
        if (sent > 0) {
            log.info("invite-expiry-scheduler.sent count={}", sent);
        }
    }

    private int notifyDirector(Organization organization, String kind, String title, String body, String href, String dedupKey) {
        if (organization == null) {
            return 0;
        }
        return userRepository
                .findFirstByOrganization_IdAndRoleAndDeletedAtIsNull(organization.getId(), Role.DIRECTOR)
                .map(director -> {
                    notificationPublisher.publish(director.getId(), kind, title, body, href, dedupKey);
                    return 1;
                })
                .orElse(0);
    }
}

package com.qstory.backend.tutor.lesson.service;

import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.notification.service.NotificationPublisher;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.lesson.LessonStatus;
import com.qstory.backend.tutor.lesson.entity.Lesson;
import com.qstory.backend.tutor.lesson.repository.LessonRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수업 시작 30분 전, 참여 학생의 연결된 부모에게 "곧 수업이 시작해요" 알림을 보낸다.
 * LiveBranchStaleJobReaper/BetaSessionRetentionScheduler와 같은 5분 주기 폴링 패턴 - 매 tick마다
 * "지금부터 25~30분 뒤"에 시작하는 SCHEDULED 수업만 조회한다. 이 5분 폭 윈도우가 폴링 주기와
 * 정확히 맞물려 있어서(다음 tick의 윈도우가 정확히 이번 윈도우 바로 다음부터 시작), 배포/재시작
 * 없이 정상 동작하는 한 각 수업은 정확히 한 번만 이 윈도우에 걸린다. 그래도 재시작으로 한 tick이
 * 씹히거나 두 인스턴스가 동시에 뜨는 경우에 대비해, 최종 안전장치는 NotificationPublisher의
 * dedupKey(수업당 학생당 1건)다.
 */
@Component
public class LessonReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(LessonReminderScheduler.class);
    private static final Duration REMINDER_LEAD_MIN = Duration.ofMinutes(25);
    private static final Duration REMINDER_LEAD_MAX = Duration.ofMinutes(30);

    private final LessonRepository lessonRepository;
    private final NotificationPublisher notificationPublisher;

    public LessonReminderScheduler(
            LessonRepository lessonRepository, NotificationPublisher notificationPublisher) {
        this.lessonRepository = lessonRepository;
        this.notificationPublisher = notificationPublisher;
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    @Transactional
    public void sendUpcomingReminders() {
        Instant now = Instant.now();
        List<Lesson> upcoming = lessonRepository.findByStatusAndScheduledAtBetween(
                LessonStatus.SCHEDULED, now.plus(REMINDER_LEAD_MIN), now.plus(REMINDER_LEAD_MAX));
        if (upcoming.isEmpty()) {
            return;
        }
        int sent = 0;
        for (Lesson lesson : upcoming) {
            String tutorName = lesson.getTutor().getDisplayName();
            for (TutorStudent student : lesson.getStudents()) {
                AppUser parent = student.getLinkedParentUser();
                if (parent == null) continue;
                notificationPublisher.publish(
                        parent.getId(),
                        "lesson-reminder",
                        "곧 [" + lesson.getName() + "] 수업이 시작해요",
                        tutorName + " 선생님과 30분 후 수업이 예정되어 있어요.",
                        null,
                        "lesson-reminder:" + lesson.getId() + ":" + student.getId());
                sent += 1;
            }
        }
        if (sent > 0) {
            log.info("lesson-reminder-scheduler.sent count={} lessons={}", sent, upcoming.size());
        }
    }
}

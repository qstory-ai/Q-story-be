package com.qstory.backend.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.notification.entity.Notification;
import com.qstory.backend.notification.repository.NotificationRepository;
import com.qstory.backend.parent.notification.entity.NotificationSettings;
import com.qstory.backend.parent.notification.repository.NotificationSettingsRepository;
import com.qstory.backend.push.service.PushDispatcher;
import com.qstory.backend.push.service.PushMessage;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 075: 앱 푸시는 알림이 실제로 저장됐을 때만 나간다 - 설정으로 꺼졌거나 중복이면 푸시도 없다. */
class NotificationPublisherTest {

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final NotificationSettingsRepository settings = mock(NotificationSettingsRepository.class);
    private final PushDispatcher push = mock(PushDispatcher.class);
    private final NotificationPublisher publisher = new NotificationPublisher(notifications, users, settings, push);
    private final UUID userId = UUID.randomUUID();

    @Test
    void savedNotificationIsPushedWithItsId() {
        when(users.getReferenceById(userId)).thenReturn(AppUser.builder().id(userId).build());
        when(notifications.save(any(Notification.class))).thenAnswer(call -> call.getArgument(0));

        publisher.publish(userId, "invite-accepted", "초대를 수락했어요", "하린 보호자", "/tutor/students", "invite:1");

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(saved.capture());
        ArgumentCaptor<PushMessage> pushed = ArgumentCaptor.forClass(PushMessage.class);
        verify(push).dispatchAfterCommit(org.mockito.ArgumentMatchers.eq(userId), pushed.capture());
        assertEquals(new PushMessage(saved.getValue().getId(), "invite-accepted", "초대를 수락했어요", "하린 보호자",
                "/tutor/students"), pushed.getValue());
    }

    @Test
    void settingsSkipDoesNotPush() {
        NotificationSettings off = mock(NotificationSettings.class);
        when(off.isLessonReportEnabled()).thenReturn(false);
        when(settings.findById(userId)).thenReturn(Optional.of(off));

        publisher.publish(userId, "tutor-report", "리포트", null, null, "tutor-report:1");

        verify(notifications, never()).save(any());
        verifyNoInteractions(push);
    }

    @Test
    void dedupSkipDoesNotPush() {
        when(notifications.existsByUser_IdAndDedupKey(userId, "tutor-report:1")).thenReturn(true);

        publisher.publish(userId, "tutor-report", "리포트", null, null, "tutor-report:1");

        verify(notifications, never()).save(any());
        verifyNoInteractions(push);
    }
}

package com.qstory.backend.launchnotification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.launchnotification.dto.LaunchNotificationSubmission;
import com.qstory.backend.launchnotification.entity.LaunchNotificationRequest;
import com.qstory.backend.launchnotification.repository.LaunchNotificationRequestRepository;
import com.qstory.backend.launchnotification.service.LaunchNotificationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LaunchNotificationServiceTest {

    private final LaunchNotificationRequestRepository repository = mock(LaunchNotificationRequestRepository.class);
    private final LaunchNotificationService service = new LaunchNotificationService(repository);

    private static LaunchNotificationSubmission submission(String phone, Boolean wantsContact) {
        return new LaunchNotificationSubmission("보호자", "a@b.co", phone, "GIRL", "5세", "지인", wantsContact);
    }

    private LaunchNotificationRequest saved() {
        ArgumentCaptor<LaunchNotificationRequest> captor = ArgumentCaptor.forClass(LaunchNotificationRequest.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void declinedContactDropsPhoneEvenWhenSent() {
        service.submit(submission("010-1234-5678", false));
        assertNull(saved().getPhone());
    }

    @Test
    void declinedContactWithoutPhoneSucceeds() {
        service.submit(submission(null, false));
        assertNull(saved().getPhone());
    }

    @Test
    void wantedContactKeepsPhone() {
        service.submit(submission("010-1234-5678", true));
        assertEquals("010-1234-5678", saved().getPhone());
    }

    @Test
    void wantedContactWithoutPhoneIsRejected() {
        ApiException error = assertThrows(ApiException.class, () -> service.submit(submission(null, true)));
        assertEquals(ErrorCode.VALIDATION_FAILED, error.code());
    }

    @Test
    void wantedContactWithBadPhoneIsRejected() {
        assertThrows(ApiException.class, () -> service.submit(submission("abc", true)));
    }
}

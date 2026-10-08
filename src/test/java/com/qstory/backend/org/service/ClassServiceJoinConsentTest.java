package com.qstory.backend.org.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.dto.ConsentPayload;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.JwtService;
import com.qstory.backend.identity.service.ConsentService;
import com.qstory.backend.identity.service.UserSummaryFactory;
import com.qstory.backend.identity.util.AuthValidator;
import com.qstory.backend.org.dto.JoinClassRequest;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.util.JoinCodeGenerator;
import com.qstory.backend.tutor.service.TutorStudentService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class ClassServiceJoinConsentTest {

    private final ClassGroupRepository classGroups = mock(ClassGroupRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final ConsentService consents = mock(ConsentService.class);
    private final ClassService service = new ClassService(
            classGroups, null, null, users, null, mock(JoinCodeGenerator.class), mock(AuthValidator.class),
            mock(PasswordEncoder.class), mock(JwtService.class), mock(TutorStudentService.class),
            mock(UserSummaryFactory.class), null, null, null, consents, null, null);

    private JoinClassRequest request(ConsentPayload consent) {
        return new JoinClassRequest("SUN123", "loginid", "a@b.co", "password1", "이름", "아이", 2020, null, consent);
    }

    @Test
    void joinWithoutConsentIsRejectedBeforeSaving() {
        when(classGroups.findByJoinCode("SUN123")).thenReturn(Optional.of(ClassGroup.builder().build()));
        doThrow(ApiException.contractError(ErrorCode.CONSENT_REQUIRED, "x")).when(consents).requireSignupConsents(any());
        ApiException e = assertThrows(ApiException.class, () -> service.join(request(null)));
        assertEquals(ErrorCode.CONSENT_REQUIRED, e.code());
        verify(users, never()).saveOrThrowDuplicate(any(), anyString());
    }

    @Test
    void joinWithConsentRecordsItAsClassJoinSignup() {
        ConsentPayload ok = new ConsentPayload("2026-10-v1", true, true, false);
        when(classGroups.findByJoinCode("SUN123")).thenReturn(Optional.of(ClassGroup.builder().build()));
        when(users.saveOrThrowDuplicate(any(), anyString())).thenAnswer(i -> i.getArgument(0));
        service.join(request(ok));
        verify(consents).recordSignup(any(AppUser.class), eq(ok), eq("CLASS_JOIN_SIGNUP"));
    }
}

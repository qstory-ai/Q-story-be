package com.qstory.backend.org.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.ConsentPayload;
import com.qstory.backend.identity.dto.SignupOrganizationOwnerRequest;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.service.AuthService;
import com.qstory.backend.org.dto.JoinClassRequest;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.util.JoinCodeGenerator;
import com.qstory.backend.tutor.service.TutorStudentService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 반 코드 가입은 계정 생성을 가입과 같은 길(AuthService.registerAccount)로 보내고, 그 뒤에만 아이를 반에 올린다. */
class ClassServiceJoinConsentTest {

    private final ClassGroupRepository classGroups = mock(ClassGroupRepository.class);
    private final AuthService authService = mock(AuthService.class);
    private final TutorStudentService students = mock(TutorStudentService.class);
    private final ClassService service = new ClassService(
            classGroups, null, null, mock(AppUserRepository.class), null, mock(JoinCodeGenerator.class), authService,
            students, null, null, null, null, null);

    private JoinClassRequest request(ConsentPayload consent) {
        return new JoinClassRequest("SUN123", "loginid", "a@b.co", "password1", "이름", "아이", 2020, null, consent);
    }

    @Test
    void joinStopsBeforeEnrollingWhenRegistrationIsRejected() {
        when(classGroups.findByJoinCode("SUN123")).thenReturn(Optional.of(ClassGroup.builder().build()));
        when(authService.registerAccount(any(), any(), any()))
                .thenThrow(ApiException.contractError(ErrorCode.CONSENT_REQUIRED, "x"));
        ApiException e = assertThrows(ApiException.class, () -> service.join(request(null)));
        assertEquals(ErrorCode.CONSENT_REQUIRED, e.code());
        verify(students, never()).enrollParentInClass(any(), any(), any(), any(), any(), any());
    }

    @Test
    void joinRegistersAParentAsClassJoinSignupAndEnrollsTheChild() {
        ConsentPayload ok = new ConsentPayload("2026-10-v2", true, true, false, true, true);
        ClassGroup classGroup = ClassGroup.builder().build();
        AppUser parent = AppUser.builder().role(Role.PARENT).build();
        when(classGroups.findByJoinCode("SUN123")).thenReturn(Optional.of(classGroup));
        when(authService.registerAccount(eq(Role.PARENT), any(), eq("CLASS_JOIN_SIGNUP"))).thenReturn(parent);

        service.join(request(ok));

        ArgumentCaptor<SignupOrganizationOwnerRequest> signup = ArgumentCaptor.forClass(SignupOrganizationOwnerRequest.class);
        verify(authService).registerAccount(eq(Role.PARENT), signup.capture(), eq("CLASS_JOIN_SIGNUP"));
        assertEquals("loginid", signup.getValue().loginId());
        assertEquals(ok, signup.getValue().consents());
        verify(students).enrollParentInClass(eq(parent), eq(classGroup), eq("아이"), eq(2020), isNull(), isNull());
        verify(authService).issueResponse(parent, true, null);
    }
}

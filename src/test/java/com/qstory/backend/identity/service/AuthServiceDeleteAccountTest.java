package com.qstory.backend.identity.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.DeleteAccountRequest;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AccountDeletionFeedbackRepository;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.voiceresearch.service.VoiceResearchService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AuthServiceDeleteAccountTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AccountDeletionFeedbackRepository feedback = mock(AccountDeletionFeedbackRepository.class);
    private final VoiceResearchService voice = mock(VoiceResearchService.class);
    private final AccountErasureService erasure = mock(AccountErasureService.class);
    private final AuthService service = new AuthService(
            users, null, feedback, null, null, null, null, null, null, null, null,
            null, voice, erasure, null);

    private AppUser stub(Role role) {
        AppUser user = AppUser.builder().id(UUID.randomUUID()).role(role).loginId("a").displayName("a").build();
        when(users.findByIdAndDeletedAtIsNull(user.getId())).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void parentDeletionWithdrawsVoiceThenSavesReasonThenErases() {
        AppUser parent = stub(Role.PARENT);
        service.deleteAccount(new CurrentUser(parent.getId(), Role.PARENT, null),
                new DeleteAccountRequest("기타", "그냥요"));
        InOrder order = inOrder(voice, feedback, erasure);
        order.verify(voice).withdrawForDeletedAccount(parent.getId());
        order.verify(feedback).save(any());
        order.verify(erasure).erase(parent);
    }

    @Test
    void tutorDeletionSkipsVoiceAndErases() {
        AppUser tutor = stub(Role.TUTOR);
        service.deleteAccount(new CurrentUser(tutor.getId(), Role.TUTOR, null), new DeleteAccountRequest("기타", null));
        verify(voice, never()).withdrawForDeletedAccount(any());
        verify(erasure).erase(tutor);
    }
}

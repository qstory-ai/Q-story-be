package com.qstory.backend.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.util.SupabaseStorageClient;
import com.qstory.backend.config.AppProperties;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 프로필 사진: 업로드는 트랜잭션 밖에서 먼저, 계정 행 변경은 그 뒤 짧은 트랜잭션, 실패하면 올린 파일 정리. */
class AuthServiceProfileImageTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final SupabaseStorageClient storage = mock(SupabaseStorageClient.class);
    private final AppProperties config = mock(AppProperties.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final AuthService service = new AuthService(
            users, null, null, null, null, null, null, null, null, storage, config,
            mock(UserSummaryFactory.class), null, null, null, null, new TransactionTemplate(transactions));

    private final AppUser tutor = AppUser.builder()
            .id(UUID.randomUUID()).role(Role.TUTOR).loginId("t").displayName("t").build();
    private final CurrentUser caller = new CurrentUser(tutor.getId(), Role.TUTOR, null);

    AuthServiceProfileImageTest() {
        when(config.supabase()).thenReturn(
                new AppProperties.Supabase("https://example.supabase.co", "key", null, null, null, null, "profiles-bucket"));
        when(users.findByIdAndDeletedAtIsNull(tutor.getId())).thenReturn(Optional.of(tutor));
        when(storage.upload(anyString(), anyString(), any(), anyString())).thenReturn(true);
    }

    @Test
    void uploadsBeforeOpeningTheTransactionThenDeletesThePreviousPhoto() throws Exception {
        String previous = "profiles/" + tutor.getId() + "/old.png";
        tutor.setProfileImageObjectName(previous);

        service.uploadProfileImage(caller, png());

        InOrder order = inOrder(storage, transactions, users);
        order.verify(storage).upload(eq("profiles-bucket"), anyString(), any(), eq("image/png"));
        order.verify(transactions).getTransaction(any(TransactionDefinition.class));
        order.verify(users).save(tutor);
        order.verify(transactions).commit(any());
        order.verify(storage).delete("profiles-bucket", previous);
        assertTrue(tutor.getProfileImageObjectName().startsWith("profiles/" + tutor.getId() + "/"));
    }

    @Test
    void failedSaveDeletesTheJustUploadedFileAndKeepsThePreviousOne() throws Exception {
        String previous = "profiles/" + tutor.getId() + "/old.png";
        tutor.setProfileImageObjectName(previous);
        when(users.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> service.uploadProfileImage(caller, png()));

        ArgumentCaptor<String> uploaded = ArgumentCaptor.forClass(String.class);
        verify(storage).upload(eq("profiles-bucket"), uploaded.capture(), any(), eq("image/png"));
        verify(storage).delete("profiles-bucket", uploaded.getValue());
        verify(storage, never()).delete("profiles-bucket", previous);
    }

    @Test
    void failedUploadNeverOpensATransaction() throws Exception {
        when(storage.upload(anyString(), anyString(), any(), anyString())).thenReturn(false);

        com.qstory.backend.common.error.ApiException error = assertThrows(
                com.qstory.backend.common.error.ApiException.class, () -> service.uploadProfileImage(caller, png()));

        assertEquals(502, error.statusCode());
        verify(transactions, never()).getTransaction(any());
        verify(users, never()).save(any());
    }

    private static MockMultipartFile png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("image", "a.png", "image/png", out.toByteArray());
    }
}

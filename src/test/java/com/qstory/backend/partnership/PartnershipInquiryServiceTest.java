package com.qstory.backend.partnership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.config.DiscordProperties;
import com.qstory.backend.discord.service.DiscordChannel;
import com.qstory.backend.discord.service.DiscordMention;
import com.qstory.backend.discord.service.DiscordNotifier;
import com.qstory.backend.partnership.dto.PartnershipInquirySubmission;
import com.qstory.backend.partnership.entity.PartnershipInquiry;
import com.qstory.backend.partnership.entity.PartnershipInquiry.Role;
import com.qstory.backend.partnership.repository.PartnershipInquiryRepository;
import com.qstory.backend.partnership.service.PartnershipInquiryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PartnershipInquiryServiceTest {

    private final PartnershipInquiryRepository repository = mock(PartnershipInquiryRepository.class);
    private final DiscordNotifier notifier = mock(DiscordNotifier.class);
    private final DiscordProperties properties = new DiscordProperties(true, "m", "d", "n", "1", "2", true, true, true,
            null, "https://admin.test/inquiries", 100, "80,95", 5.0, null);
    private final PartnershipInquiryService service = new PartnershipInquiryService(repository, notifier, properties);

    private static PartnershipInquirySubmission submission(String role, String email, String phone, String message) {
        return new PartnershipInquirySubmission(" 김담당 ", "한빛출판사", role, " 편집팀장 ", email, phone, message, true);
    }

    private PartnershipInquiry saved() {
        ArgumentCaptor<PartnershipInquiry> captor = ArgumentCaptor.forClass(PartnershipInquiry.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void savesTrimmedInquiryAndAlertsOpsWithRoleOnly() {
        service.submit(submission("publisher", "a@b.co", "", "그림책 협업을 문의드립니다."));

        PartnershipInquiry row = saved();
        assertEquals("김담당", row.getName());
        assertEquals(Role.PUBLISHER, row.getRole());
        assertEquals("편집팀장", row.getJobTitle());
        assertNull(row.getPhone());
        assertTrue(row.getCreatedAt() != null);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notifier).send(eq(DiscordChannel.NEWS), text.capture(), eq(List.of(DiscordMention.OPS)));
        assertTrue(text.getValue().contains("새 협업 문의(출판사·콘텐츠) 1건 — 관리 화면 확인"));
        assertTrue(text.getValue().contains("<https://admin.test/inquiries>"));
        // 개인정보·문의 내용은 디스코드로 보내지 않는다.
        assertFalse(text.getValue().contains("김담당"));
        assertFalse(text.getValue().contains("한빛출판사"));
        assertFalse(text.getValue().contains("a@b.co"));
        assertFalse(text.getValue().contains("그림책"));
        assertFalse(text.getValue().contains("편집팀장"));
    }

    @Test
    void keepsOptionalPhone() {
        service.submit(submission("ORGANIZATION", "a@b.co", "02-123-4567", "문의"));
        assertEquals("02-123-4567", saved().getPhone());
    }

    @Test
    void rejectsUnknownRoleBadEmailBadPhoneAndEmptyMessageWithoutSavingOrAlerting() {
        for (PartnershipInquirySubmission bad : List.of(
                submission("SCHOOL", "a@b.co", null, "문의"),
                submission(null, "a@b.co", null, "문의"),
                submission("OTHER", "not-an-email", null, "문의"),
                submission("OTHER", "a@b.co", "abc", "문의"),
                submission("OTHER", "a@b.co", null, "  "),
                submission("OTHER", "a@b.co", null, "가".repeat(2001)),
                new PartnershipInquirySubmission("김", "소속", "OTHER", null, "a@b.co", null, "문의", false),
                new PartnershipInquirySubmission("김", "소속", "OTHER", null, "a@b.co", null, "문의", null))) {
            ApiException error = assertThrows(ApiException.class, () -> service.submit(bad));
            assertEquals(ErrorCode.VALIDATION_FAILED, error.code());
        }
        verify(repository, never()).save(any());
        verify(notifier, never()).send(any(), anyString(), any());
    }

    @Test
    void saveFailureSkipsAlert() {
        when(repository.save(any())).thenThrow(new IllegalStateException("db down"));
        assertThrows(IllegalStateException.class,
                () -> service.submit(submission("OTHER", "a@b.co", null, "문의")));
        verify(notifier, never()).send(any(), anyString(), any());
    }

    @Test
    void messageOmitsBlankLink() {
        assertEquals("**[협업 문의]** 새 협업 문의(교육기관) 1건 — 관리 화면 확인",
                PartnershipInquiryService.discordMessage(Role.ORGANIZATION, " "));
    }
}

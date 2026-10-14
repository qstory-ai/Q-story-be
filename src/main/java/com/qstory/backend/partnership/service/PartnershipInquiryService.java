package com.qstory.backend.partnership.service;

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
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 소개 페이지 협업 문의. 로그인 없는 익명 제출이라 LaunchNotificationService처럼 서버에서 형태를 다시 확인한다.
 * 저장되면 디스코드 #소식에 바로 @운영 멘션으로 알린다 - 이름·연락처·내용은 보내지 않고 소속 종류만(DiscordMessages 원칙).
 */
@Service
public class PartnershipInquiryService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[0-9+\\-\\s()]{9,20}$");

    private final PartnershipInquiryRepository repository;
    private final DiscordNotifier notifier;
    private final DiscordProperties discordProperties;

    public PartnershipInquiryService(
            PartnershipInquiryRepository repository, DiscordNotifier notifier, DiscordProperties discordProperties) {
        this.repository = repository;
        this.notifier = notifier;
        this.discordProperties = discordProperties;
    }

    /** 저장(리포지토리 자체 트랜잭션) 뒤 알림 - 저장이 실패하면 알리지 않고, 알림 실패는 저장을 되돌리지 않는다. */
    public void submit(PartnershipInquirySubmission submission) {
        String name = requireText(submission.name(), 60, "이름을 입력해 주세요.");
        String affiliation = requireText(submission.affiliation(), 100, "소속을 입력해 주세요.");
        Role role = parseRole(submission.role());
        String jobTitle = optionalText(submission.jobTitle(), 60, "직급/역할은 60자 안으로 적어 주세요.");
        String email = requireEmail(submission.email());
        String phone = optionalPhone(submission.phone());
        String message = requireText(submission.message(), 2000, "문의 내용을 입력해 주세요.");
        if (!Boolean.TRUE.equals(submission.privacyConsent())) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "개인정보 수집 및 이용에 동의해 주세요.");
        }

        repository.save(PartnershipInquiry.builder()
                .name(name)
                .affiliation(affiliation)
                .role(role)
                .jobTitle(jobTitle)
                .email(email)
                .phone(phone)
                .message(message)
                .createdAt(Instant.now())
                .build());

        notifier.send(DiscordChannel.NEWS, discordMessage(role, discordProperties.newsLink()),
                List.of(DiscordMention.OPS));
    }

    /** 소속 종류만 넣는다 - 이름·소속명·연락처·내용은 넣지 않는다. */
    public static String discordMessage(Role role, String link) {
        String text = "**[협업 문의]** 새 협업 문의(" + role.label() + ") 1건 — 관리 화면 확인";
        if (link != null && !link.isBlank()) {
            text += "\n<" + link.trim() + ">";
        }
        return text;
    }

    private Role parseRole(String value) {
        try {
            return Role.valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException invalid) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "소속 종류(기관/출판사/기타)를 선택해 주세요.");
        }
    }

    private String requireEmail(String value) {
        String email = requireText(value, 254, "이메일을 입력해 주세요.");
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "올바른 이메일 주소를 입력해 주세요.");
        }
        return email;
    }

    private String optionalPhone(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return null;
        if (!PHONE_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "올바른 연락처를 입력해 주세요.");
        }
        return trimmed;
    }

    private String optionalText(String value, int maxLength, String safeDetail) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > maxLength) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, safeDetail);
        }
        return trimmed;
    }

    private String requireText(String value, int maxLength, String safeDetail) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.length() > maxLength) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, safeDetail);
        }
        return trimmed;
    }
}

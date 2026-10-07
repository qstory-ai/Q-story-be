package com.qstory.backend.identity.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.dto.ConsentPayload;
import com.qstory.backend.identity.dto.ConsentRecordRequest;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.entity.UserConsent;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.repository.UserConsentRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.parent.notification.entity.NotificationSettings;
import com.qstory.backend.parent.notification.repository.NotificationSettingsRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 동의 이력 기록과 가입 시 필수 동의 검사. 호출하는 가입 트랜잭션 안에서 함께 저장된다. */
@Service
public class ConsentService {

    private static final Set<String> TYPES =
            Set.of("TERMS", "PRIVACY", "MARKETING", "CHILD_REPORT_SCOPE", "VOICE_RAW");
    private static final Set<String> SOURCES =
            Set.of("SIGNUP", "OAUTH_SIGNUP", "CLASS_JOIN_SIGNUP", "ONBOARDING", "MYPAGE");
    private static final int MAX_ITEMS = 20;

    private final UserConsentRepository consentRepository;
    private final NotificationSettingsRepository notificationSettingsRepository;
    private final AppUserRepository userRepository;

    public ConsentService(
            UserConsentRepository consentRepository, NotificationSettingsRepository notificationSettingsRepository,
            AppUserRepository userRepository) {
        this.consentRepository = consentRepository;
        this.notificationSettingsRepository = notificationSettingsRepository;
        this.userRepository = userRepository;
    }

    /** 계정을 만들기 전에 부른다 - 필수 동의(이용약관·개인정보)와 버전이 없으면 400 CONSENT_REQUIRED. */
    public void requireSignupConsents(ConsentPayload consents) {
        if (consents == null || !consents.terms() || !consents.privacy()
                || consents.version() == null || consents.version().isBlank() || consents.version().length() > 40) {
            throw ApiException.contractError(ErrorCode.CONSENT_REQUIRED, "이용약관과 개인정보 처리방침에 동의해 주세요.");
        }
    }

    /** 가입 직후(같은 트랜잭션) 약관·개인정보·마케팅 동의를 이력으로 남기고 마케팅 알림 설정을 동의값으로 저장한다. */
    @Transactional
    public void recordSignup(AppUser user, ConsentPayload consents, String source) {
        requireSignupConsents(consents);
        Instant now = Instant.now();
        consentRepository.saveAll(List.of(
                row(user.getId(), "TERMS", consents.version(), consents.terms(), source, now),
                row(user.getId(), "PRIVACY", consents.version(), consents.privacy(), source, now),
                row(user.getId(), "MARKETING", consents.version(), consents.marketing(), source, now)));
        notificationSettingsRepository.save(NotificationSettings.builder()
                .user(user)
                .marketingEnabled(consents.marketing())
                .updatedAt(now)
                .build());
    }

    @Transactional
    public void record(CurrentUser caller, ConsentRecordRequest request) {
        if (request == null || request.source() == null || !SOURCES.contains(request.source())) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "동의 출처가 올바르지 않아요.");
        }
        if (request.items() == null || request.items().isEmpty() || request.items().size() > MAX_ITEMS) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "저장할 동의 항목이 없어요.");
        }
        UUID userId = caller.userId();
        Instant now = Instant.now();
        List<UserConsent> rows = new ArrayList<>();
        for (ConsentRecordRequest.Item item : request.items()) {
            if (item == null || item.type() == null || !TYPES.contains(item.type())
                    || item.version() == null || item.version().isBlank() || item.version().length() > 40) {
                throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "동의 항목이 올바르지 않아요.");
            }
            rows.add(row(userId, item.type(), item.version(), item.agreed(), request.source(), now));
        }
        consentRepository.saveAll(rows);
    }

    private static UserConsent row(
            UUID userId, String type, String version, boolean agreed, String source, Instant now) {
        return UserConsent.builder().userId(userId).consentType(type).version(version).agreed(agreed)
                .source(source).createdAt(now).build();
    }
}

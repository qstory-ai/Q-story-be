package com.qstory.backend.identity.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.dto.ConsentPayload;
import com.qstory.backend.identity.dto.ConsentRecordRequest;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.entity.UserConsent;
import com.qstory.backend.identity.repository.UserConsentRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.repository.OrganizationRepository;
import com.qstory.backend.parent.notification.entity.NotificationSettings;
import com.qstory.backend.parent.notification.repository.NotificationSettingsRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 동의 이력 기록과 가입 시 필수 동의 검사. 호출하는 가입 트랜잭션 안에서 함께 저장된다. */
@Service
public class ConsentService {

    /** 만 14세 미만 아이의 법정대리인(보호자) 확인 - 보호자 계정의 필수 동의(A5). */
    public static final String GUARDIAN = "GUARDIAN";
    /** 개인정보 국외 이전 동의 - 모든 고객 계정의 필수 동의(A9). */
    public static final String OVERSEAS_TRANSFER = "OVERSEAS_TRANSFER";
    /** 기관이 원아 보호자 동의를 받아 이용한다는 확인 - 기관 단위 기록(Organization.guardianConsentAck*), 원장 계정에만. */
    public static final String ORG_GUARDIAN_ACK = "ORG_GUARDIAN_ACK";

    private static final Set<String> TYPES = Set.of(
            "TERMS", "PRIVACY", "MARKETING", "CHILD_REPORT_SCOPE", "VOICE_RAW", GUARDIAN, OVERSEAS_TRANSFER);
    private static final Set<String> SOURCES =
            Set.of("SIGNUP", "OAUTH_SIGNUP", "CLASS_JOIN_SIGNUP", "ONBOARDING", "MYPAGE", "RECONSENT");
    private static final int MAX_ITEMS = 20;

    private final UserConsentRepository consentRepository;
    private final NotificationSettingsRepository notificationSettingsRepository;
    private final OrganizationRepository organizationRepository;

    public ConsentService(
            UserConsentRepository consentRepository, NotificationSettingsRepository notificationSettingsRepository,
            OrganizationRepository organizationRepository) {
        this.consentRepository = consentRepository;
        this.notificationSettingsRepository = notificationSettingsRepository;
        this.organizationRepository = organizationRepository;
    }

    /**
     * 계정을 만들기 전에 부른다 - 필수 동의와 버전이 없으면 400 CONSENT_REQUIRED. 이용약관·개인정보·국외 이전은 모든 역할,
     * 법정대리인 확인은 보호자(PARENT)만 필수다. guardian·overseasTransfer를 보내지 않는 예전 화면도 여기서 막힌다 -
     * 새로 고치면 새 동의 화면이 나온다.
     */
    public void requireSignupConsents(ConsentPayload consents, Role role) {
        if (consents == null || !consents.terms() || !consents.privacy()
                || consents.version() == null || consents.version().isBlank() || consents.version().length() > 40) {
            throw ApiException.contractError(ErrorCode.CONSENT_REQUIRED, "이용약관과 개인정보 처리방침에 동의해 주세요.");
        }
        if (!consents.overseasTransfer() || (role == Role.PARENT && !consents.guardian())) {
            throw ApiException.contractError(ErrorCode.CONSENT_REQUIRED,
                    role == Role.PARENT
                            ? "법정대리인(보호자) 확인과 개인정보 국외 이전에 동의해 주세요. 화면을 새로 고친 뒤 다시 가입해 주세요."
                            : "개인정보 국외 이전에 동의해 주세요. 화면을 새로 고친 뒤 다시 가입해 주세요.");
        }
    }

    /** 가입 직후(같은 트랜잭션) 동의를 이력으로 남기고 마케팅 알림 설정을 동의값으로 저장한다. */
    @Transactional
    public void recordSignup(AppUser user, ConsentPayload consents, String source) {
        requireSignupConsents(consents, user.getRole());
        Instant now = Instant.now();
        List<UserConsent> rows = new ArrayList<>(List.of(
                row(user.getId(), "TERMS", consents.version(), consents.terms(), source, now),
                row(user.getId(), "PRIVACY", consents.version(), consents.privacy(), source, now),
                row(user.getId(), "MARKETING", consents.version(), consents.marketing(), source, now),
                row(user.getId(), OVERSEAS_TRANSFER, consents.version(), true, source, now)));
        if (user.getRole() == Role.PARENT) {
            rows.add(row(user.getId(), GUARDIAN, consents.version(), true, source, now));
        }
        consentRepository.saveAll(rows);
        NotificationSettings existing = notificationSettingsRepository.findById(user.getId()).orElse(null);
        if (existing != null) {
            existing.setMarketingEnabled(consents.marketing());
            existing.setUpdatedAt(now);
            notificationSettingsRepository.save(existing);
        } else {
            notificationSettingsRepository.save(NotificationSettings.builder()
                    .user(user)
                    .marketingEnabled(consents.marketing())
                    .updatedAt(now)
                    .build());
        }
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

    /** 기관 보호자 동의 확인을 원장 계정의 동의 이력에도 한 줄 남긴다(기관 행은 마지막 확인만 담는다). */
    @Transactional
    public void recordOrganizationGuardianAck(UUID directorUserId, String version, String source) {
        consentRepository.save(row(directorUserId, ORG_GUARDIAN_ACK, version, true, source, Instant.now()));
    }

    /**
     * 로그인한 계정이 아직 받지 않은 필수 동의 - 비어 있지 않으면 프런트가 다시 동의 화면을 띄운다(약관 2026-10-v2 이전 가입자).
     * 유형마다 가장 최근 기록이 동의여야 받은 것으로 본다. STAFF(내부 계정)는 대상이 아니다.
     */
    @Transactional(readOnly = true)
    public List<String> pendingConsents(AppUser user) {
        if (user.getRole() == null || user.getRole() == Role.STAFF || user.getId() == null) return List.of();
        List<String> required = user.getRole() == Role.PARENT ? List.of(GUARDIAN, OVERSEAS_TRANSFER) : List.of(OVERSEAS_TRANSFER);
        Map<String, Boolean> latest = new HashMap<>();
        for (UserConsent consent : consentRepository.findByUserIdAndConsentTypeInOrderByCreatedAtDesc(user.getId(), required)) {
            latest.putIfAbsent(consent.getConsentType(), consent.isAgreed());
        }
        List<String> pending = new ArrayList<>();
        for (String type : required) {
            if (!Boolean.TRUE.equals(latest.get(type))) pending.add(type);
        }
        if (user.getRole() == Role.DIRECTOR && user.getOrganization() != null
                && !organizationRepository.existsByIdAndGuardianConsentAckAtIsNotNull(user.getOrganization().getId())) {
            pending.add(ORG_GUARDIAN_ACK);
        }
        return List.copyOf(pending);
    }

    private static UserConsent row(
            UUID userId, String type, String version, boolean agreed, String source, Instant now) {
        return UserConsent.builder().userId(userId).consentType(type).version(version).agreed(agreed)
                .source(source).createdAt(now).build();
    }
}

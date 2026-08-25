package com.qstory.backend.launchnotification.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.launchnotification.dto.LaunchNotificationSubmission;
import com.qstory.backend.launchnotification.entity.LaunchNotificationRequest;
import com.qstory.backend.launchnotification.entity.LaunchNotificationRequest.ChildGender;
import com.qstory.backend.launchnotification.repository.LaunchNotificationRequestRepository;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 무료 데모를 쓰기 전 보호자가 남기는 "정식 출시 때 연락해 주세요" 신청. 로그인이 전혀 없는
 * 익명 방문자가 제출하므로(AuthValidator의 회원가입 검증과 달리 인증된 세션이 없다), 클라이언트
 * 쪽 검증을 그대로 믿지 않고 여기서 형태를 다시 확인한다 - AuthValidator.validateSignup()과
 * 같은 이유.
 */
@Service
public class LaunchNotificationService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[0-9+\\-\\s]{9,20}$");

    private final LaunchNotificationRequestRepository repository;

    public LaunchNotificationService(LaunchNotificationRequestRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void submit(LaunchNotificationSubmission submission) {
        String parentName = requireText(submission.parentName(), 60, "보호자 이름을 입력해 주세요.");
        String email = optionalEmail(submission.email());
        String phone = requireText(submission.phone(), 30, "전화번호를 입력해 주세요.");
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "올바른 전화번호를 입력해 주세요.");
        }
        ChildGender childGender = parseGender(submission.childGender());
        String childAge = requireText(submission.childAge(), 20, "아이 나이를 입력해 주세요.");
        String discoverySource = requireText(submission.discoverySource(), 200, "어떻게 알게 되셨는지 알려주세요.");
        if (submission.wantsContact() == null) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "연락 여부를 선택해 주세요.");
        }

        repository.save(LaunchNotificationRequest.builder()
                .parentName(parentName)
                .email(email)
                .phone(phone)
                .childGender(childGender)
                .childAge(childAge)
                .discoverySource(discoverySource)
                .wantsContact(submission.wantsContact())
                .createdAt(Instant.now())
                .build());
    }

    /** "괜찮아요"를 고른 보호자는 연락받길 원치 않으므로 이메일을 아예 안 보낼 수 있다 - 그 경우 null로 저장한다. */
    private String optionalEmail(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > 254 || !EMAIL_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "올바른 이메일 주소를 입력해 주세요.");
        }
        return trimmed;
    }

    private ChildGender parseGender(String value) {
        try {
            return ChildGender.valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException invalid) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "아이 성별을 선택해 주세요.");
        }
    }

    private String requireText(String value, int maxLength, String safeDetail) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.length() > maxLength) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, safeDetail);
        }
        return trimmed;
    }
}

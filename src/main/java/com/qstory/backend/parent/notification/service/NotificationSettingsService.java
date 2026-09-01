package com.qstory.backend.parent.notification.service;

import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.parent.notification.dto.NotificationSettingsResponse;
import com.qstory.backend.parent.notification.dto.UpdateNotificationSettingsRequest;
import com.qstory.backend.parent.notification.entity.NotificationSettings;
import com.qstory.backend.parent.notification.repository.NotificationSettingsRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자별 알림 preference 조회/수정. 회원 가입 직후에는 아직 행이 없어 기본값으로 응답하고,
 * 첫 수정 시점에 upsert한다 - "가입 후 GET → 기본값 반환" 사이에 백그라운드로 미리 만들지
 * 않는 이유는 그 편이 스키마 변경(새 컬럼 추가)에 더 잘 견디기 때문이다.
 */
@Service
public class NotificationSettingsService {

    private final NotificationSettingsRepository repository;
    private final AppUserRepository userRepository;

    public NotificationSettingsService(
            NotificationSettingsRepository repository, AppUserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public NotificationSettingsResponse read(CurrentUser caller) {
        return repository.findById(caller.userId())
                .map(NotificationSettingsResponse::of)
                .orElseGet(NotificationSettingsResponse::defaults);
    }

    @Transactional
    public NotificationSettingsResponse update(CurrentUser caller, UpdateNotificationSettingsRequest request) {
        NotificationSettings settings = repository.findById(caller.userId())
                .orElseGet(() -> {
                    // 첫 수정 - user 프록시를 붙이고 기본값으로 새 행을 만든다. @MapsId가 user.id를
                    // 그대로 이 엔티티의 PK로 채운다.
                    AppUser user = userRepository.getReferenceById(caller.userId());
                    return NotificationSettings.builder()
                            .user(user)
                            .marketingEnabled(true)
                            .updatedAt(Instant.now())
                            .build();
                });
        if (request.marketingEnabled() != null) settings.setMarketingEnabled(request.marketingEnabled());
        settings.setUpdatedAt(Instant.now());
        return NotificationSettingsResponse.of(repository.save(settings));
    }
}

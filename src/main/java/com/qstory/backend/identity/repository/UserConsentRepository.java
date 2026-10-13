package com.qstory.backend.identity.repository;

import com.qstory.backend.identity.entity.UserConsent;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserConsentRepository extends JpaRepository<UserConsent, UUID> {

    /** 다시 동의 받을 항목 계산용 - 유형별 가장 최근 기록이 앞에 온다. */
    List<UserConsent> findByUserIdAndConsentTypeInOrderByCreatedAtDesc(UUID userId, Collection<String> consentTypes);
}

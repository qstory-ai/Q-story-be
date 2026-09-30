package com.qstory.backend.org;

import java.time.Instant;

/** 기관·개인 구독의 유료 접근 상태. 결제 확인(PaymentService)이 ACTIVE와 만료 시각을 기록한다. */
public enum SubscriptionStatus {
    NONE,
    TRIALING,
    ACTIVE,
    EXPIRED;

    /** 이 상태가 권한 제한된(entitlement-gated) 스토리에 접근하기에 충분한지 여부. */
    public boolean grantsAccess() {
        return this == TRIALING || this == ACTIVE;
    }

    /** A paid subscription is only usable until its server-issued expiry time. */
    public boolean grantsAccessAt(Instant expiresAt, Instant now) {
        return grantsAccess() && (expiresAt == null || expiresAt.isAfter(now));
    }

    public SubscriptionStatus effectiveAt(Instant expiresAt, Instant now) {
        return grantsAccess() && expiresAt != null && !expiresAt.isAfter(now) ? EXPIRED : this;
    }
}

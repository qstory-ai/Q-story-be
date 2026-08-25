package com.qstory.backend.org;

/** 기관의 유료 접근 상태. 아직 결제 게이트웨이는 연동되어 있지 않으며, 이는 단지 상태/게이트에 불과하다. */
public enum SubscriptionStatus {
    NONE,
    TRIALING,
    ACTIVE,
    EXPIRED;

    /** 이 상태가 권한 제한된(entitlement-gated) 스토리에 접근하기에 충분한지 여부. */
    public boolean grantsAccess() {
        return this == TRIALING || this == ACTIVE;
    }
}

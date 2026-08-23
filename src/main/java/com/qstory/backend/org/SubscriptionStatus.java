package com.qstory.backend.org;

/** An organization's paid-access state. No payment gateway is wired up yet - this is just the status/gate. */
public enum SubscriptionStatus {
    NONE,
    TRIALING,
    ACTIVE,
    EXPIRED;

    /** Whether this status is sufficient to access an entitlement-gated story. */
    public boolean grantsAccess() {
        return this == TRIALING || this == ACTIVE;
    }
}

package com.qstory.backend.org.dto;

import java.time.Instant;

public record EntitlementResponse(String subscriptionStatus, boolean grantsAccess, Instant subscriptionExpiresAt) {}

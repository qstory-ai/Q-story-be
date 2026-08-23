package com.qstory.backend.identity.security;

import com.qstory.backend.identity.Role;
import java.util.UUID;

/** The in-process identity resolved from a validated JWT's claims - never re-hit the DB for these fields. */
public record CurrentUser(UUID userId, Role role, UUID orgId, UUID classId) {}

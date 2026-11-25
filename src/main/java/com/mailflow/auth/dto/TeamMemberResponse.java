package com.mailflow.auth.dto;

import java.time.Instant;

/** A user of the caller's company. {@code role} is the user's level: ADMIN, CAMPAIGN_MANAGER or VIEWER. */
public record TeamMemberResponse(
    Long id,
    String email,
    String firstName,
    String lastName,
    String role,
    boolean enabled,
    Instant createdAt
) {}

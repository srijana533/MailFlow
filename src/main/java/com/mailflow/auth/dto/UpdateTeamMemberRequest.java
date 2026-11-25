package com.mailflow.auth.dto;

import jakarta.validation.constraints.Pattern;

/** Change a team member's role and/or enable or disable their account. Null fields are left unchanged. */
public record UpdateTeamMemberRequest(
    @Pattern(regexp = "ADMIN|CAMPAIGN_MANAGER|VIEWER",
             message = "must be ADMIN, CAMPAIGN_MANAGER or VIEWER") String role,
    Boolean enabled
) {}

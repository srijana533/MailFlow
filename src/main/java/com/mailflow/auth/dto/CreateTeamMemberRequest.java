package com.mailflow.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * An Admin adds a person to their company with a starting password, which the Admin
 * passes on to them.
 */
public record CreateTeamMemberRequest(
    @NotBlank @Email @Size(max = 255) String email,
    @NotBlank @Size(max = 100) String firstName,
    @NotBlank @Size(max = 100) String lastName,
    @NotBlank @Size(min = 8, max = 100) String password,
    @NotBlank @Pattern(regexp = "ADMIN|CAMPAIGN_MANAGER|VIEWER",
                       message = "must be ADMIN, CAMPAIGN_MANAGER or VIEWER") String role
) {}

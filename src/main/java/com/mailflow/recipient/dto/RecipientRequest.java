package com.mailflow.recipient.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record RecipientRequest(
    @NotBlank @Email @Size(max = 255) String email,
    @Size(max = 255) String name,
    @Size(max = 100) String firstName,
    @Size(max = 100) String lastName,
    @Size(max = 255) String company,
    @Size(max = 255) String course,
    Map<String, String> customFields
) {}

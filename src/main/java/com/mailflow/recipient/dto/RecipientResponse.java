package com.mailflow.recipient.dto;

import com.mailflow.recipient.entity.RecipientStatus;

import java.time.Instant;
import java.util.Map;

public record RecipientResponse(
    Long id,
    String email,
    String name,
    String firstName,
    String lastName,
    String company,
    String course,
    Map<String, String> customFields,
    RecipientStatus status,
    String importSource,
    Instant createdAt,
    Instant updatedAt
) {}

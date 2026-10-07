package com.mailflow.recipient.dto;

import java.time.Instant;

public record RecipientGroupResponse(
    Long id,
    String name,
    String description,
    int recipientCount,
    Instant createdAt,
    Instant updatedAt
) {}

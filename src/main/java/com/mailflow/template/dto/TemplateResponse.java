package com.mailflow.template.dto;

import java.time.Instant;
import java.util.List;

public record TemplateResponse(
    Long id,
    String name,
    String description,
    String subject,
    String htmlBody,
    String plainBody,
    List<String> variables,
    Instant createdAt,
    Instant updatedAt
) {}

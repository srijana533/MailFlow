package com.mailflow.attachment.dto;

import java.time.Instant;

public record AttachmentResponse(
    Long id,
    String originalName,
    String contentType,
    long sizeBytes,
    Instant uploadedAt
) {}

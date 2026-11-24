package com.mailflow.queue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

import java.time.Instant;
import java.util.List;

/**
 * Immutable message payload published to RabbitMQ for email processing.
 *
 * <p>The {@code idempotencyKey} is the globally unique delivery key
 * (format: {@code campaign_{id}_recipient_{id}}, or {@code campaign_{id}_addr_{sha256}}
 * for a campaign-only address) used to detect duplicates.
 * Workers check this key against Redis/DB before sending.
 *
 * <p>{@code recipientId} is null for a campaign-only address (no saved contact);
 * {@code toAddress} is always set.
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmailMessage(
    String idempotencyKey,
    Long campaignId,
    Long recipientId,
    String toAddress,
    String senderName,
    String senderEmail,
    String replyTo,
    String subject,
    String htmlBody,
    String plainBody,
    List<Long> attachmentIds,
    int attemptNumber,
    Instant createdAt
) {}

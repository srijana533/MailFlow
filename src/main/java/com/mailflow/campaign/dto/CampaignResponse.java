package com.mailflow.campaign.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mailflow.campaign.entity.CampaignStatus;

import java.time.Instant;
import java.util.List;

/**
 * @param targetAddressCount number of campaign-only addresses; present in every response.
 * @param targetAddresses    the campaign-only addresses themselves. Only single-campaign
 *                           responses (get, create, update) include them; the list
 *                           endpoint omits the field so a page of campaigns stays small.
 * @param customBody         the campaign's own plain-text email body. Only single-campaign
 *                           responses (get, create, update) include it, and only when the
 *                           campaign has one: absent there means the template's body is used.
 *                           The list endpoint omits it.
 * @param hasContent         whether the campaign has email content to send: a non-blank
 *                           custom body, or a non-deleted template with a non-blank HTML or
 *                           plain-text body. Present in every response (launch requires it).
 */
public record CampaignResponse(
    Long id,
    String name,
    String description,
    String subject,
    Long templateId,
    String templateName,
    String senderName,
    String senderEmail,
    String replyTo,
    CampaignStatus status,
    Instant scheduledAt,
    Instant createdAt,
    Instant updatedAt,
    Instant startedAt,
    Instant completedAt,
    long totalRecipients,
    long sentCount,
    long failedCount,
    long skippedCount,
    long bouncedCount,
    long retryCount,
    double successRate,
    String batchJobId,
    List<Long> recipientGroupIds,
    List<Long> recipientIds,
    long targetAddressCount,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<TargetAddress> targetAddresses,
    @JsonInclude(JsonInclude.Include.NON_NULL) String customBody,
    boolean hasContent
) {}
